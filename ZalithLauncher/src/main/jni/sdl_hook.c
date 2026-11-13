//
// SDL3 native hooks for single-window Android reuse, Vulkan->OpenGL fallback, and OpenGL ES compatibility.
//

#include "sdl_hook.h"

#include <android/log.h>
#include <bytehook.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#define TAG "SdlHook"
#define VULKAN_PTR ((void*) 0x6f756768)

// SDL3 SDL_WindowFlags (uint64_t)
#define SDL_WINDOW_OPENGL 0x0000000000000002ULL
#define SDL_WINDOW_HIDDEN 0x0000000000000008ULL
#define SDL_WINDOW_VULKAN 0x0000000010000000ULL

// SDL3 SDL_GLAttr enum values (verified against libSDL3.so SDL_GL_SetAttribute/GetAttribute jump tables)
#define SDL_GL_CONTEXT_MAJOR_VERSION 17
#define SDL_GL_CONTEXT_MINOR_VERSION 18
#define SDL_GL_CONTEXT_FLAGS 19
#define SDL_GL_CONTEXT_PROFILE_MASK 20
#define SDL_GL_SHARE_WITH_CURRENT_CONTEXT 21
#define SDL_GL_FRAMEBUFFER_SRGB_CAPABLE 22

// SDL3 SDL_GLContextFlag / SDL_GLProfile bits
#define SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG 0x0002
#define SDL_GL_CONTEXT_PROFILE_CORE 0x0001
#define SDL_GL_CONTEXT_PROFILE_COMPATIBILITY 0x0002
#define SDL_GL_CONTEXT_PROFILE_ES 0x0004

// EGL constants (verified against Khronos EGL/eglext.h and libSDL3.so SDL_EGL_CreateContext)
#define EGL_NONE 0x3038
#define EGL_RENDERABLE_TYPE 0x3040
#define EGL_OPENGL_ES_BIT 0x0001
#define EGL_OPENGL_ES2_BIT 0x0004
#define EGL_OPENGL_BIT 0x0008
#define EGL_OPENGL_ES3_BIT_KHR 0x0040
#define EGL_CONTEXT_CLIENT_VERSION 0x3098
#define EGL_GL_COLORSPACE_KHR 0x309D
#define EGL_CONTEXT_MINOR_VERSION_KHR 0x30FB
#define EGL_CONTEXT_FLAGS_KHR 0x30FC
#define EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR 0x30FD
#define EGL_CONTEXT_OPENGL_NO_ERROR_KHR 0x31B3

// SDL3 window creation property names
#define SDL_PROP_WINDOW_CREATE_TITLE_STRING "SDL.window.create.title"
#define SDL_PROP_WINDOW_CREATE_FLAGS_NUMBER "SDL.window.create.flags"
#define SDL_PROP_WINDOW_CREATE_OPENGL_BOOLEAN "SDL.window.create.opengl"
#define SDL_PROP_WINDOW_CREATE_VULKAN_BOOLEAN "SDL.window.create.vulkan"
#define SDL_PROP_WINDOW_CREATE_HIDDEN_BOOLEAN "SDL.window.create.hidden"

typedef void (*SDL_FunctionPointer)(void);
typedef void* (*SDL_LoadObject_t)(const char* sofile);
typedef SDL_FunctionPointer (*SDL_LoadFunction_t)(void* handle, const char* name);
typedef void (*SDL_UnloadObject_t)(void* handle);
typedef SDL_FunctionPointer (*SDL_EGL_GetProcAddress_t)(const char* proc);
typedef SDL_FunctionPointer (*SDL_GL_GetProcAddress_t)(const char* proc);
typedef bool (*SDL_GL_SetAttribute_t)(int attr, int value);
typedef bool (*SDL_GL_GetAttribute_t)(int attr, int* value);
typedef void* (*SDL_CreateWindow_t)(const char* title, int w, int h, uint64_t flags);
typedef void* (*SDL_CreateWindowWithProperties_t)(uint32_t props);
typedef void (*SDL_DestroyWindow_t)(void* window);
typedef uint64_t (*SDL_GetWindowFlags_t)(void* window);
typedef bool (*SDL_SetWindowSize_t)(void* window, int w, int h);
typedef bool (*SDL_SetWindowTitle_t)(void* window, const char* title);
typedef bool (*SDL_ShowWindow_t)(void* window);
typedef bool (*SDL_HideWindow_t)(void* window);
typedef bool (*SDL_GL_MakeCurrent_t)(void* window, void* context);
typedef void* (*SDL_GetWindowFromEvent_t)(const void* event);
typedef void* (*SDL_GetWindowFromID_t)(uint32_t id);
typedef bool (*SDL_PollEvent_t)(void* event);
typedef bool (*SDL_WaitEvent_t)(void* event);
typedef bool (*SDL_WaitEventTimeout_t)(void* event, int32_t timeoutMS);
typedef bool (*SDL_ShowSimpleMessageBox_t)(uint32_t flags, const char* title, const char* message, void* window);
typedef bool (*SDL_ShowMessageBox_t)(const void* messageboxdata, int* buttonid);
typedef int64_t (*SDL_GetNumberProperty_t)(uint32_t props, const char* name, int64_t default_value);
typedef bool (*SDL_SetNumberProperty_t)(uint32_t props, const char* name, int64_t value);
typedef bool (*SDL_GetBooleanProperty_t)(uint32_t props, const char* name, bool default_value);
typedef bool (*SDL_SetBooleanProperty_t)(uint32_t props, const char* name, bool value);
typedef const char* (*SDL_GetStringProperty_t)(uint32_t props, const char* name, const char* default_value);

typedef struct {
    uint32_t flags;
    void* window;
    const char* title;
    const char* message;
    int numbuttons;
    const void* buttons;
    const void* colorScheme;
} SDL_MessageBoxData_Layout;

typedef int (*EglChooseConfig_t)(void* dpy, const int* attrib_list, void** configs, int config_size, int* num_config);
typedef void* (*EglCreateContext_t)(void* dpy, void* config, void* share_context, const int* attrib_list);
typedef void* (*EglCreateWindowSurface_t)(void* dpy, void* config, void* win, const int* attrib_list);
typedef unsigned int (*EglSwapBuffers_t)(void* dpy, void* surface);
typedef int (*EglGetError_t)(void);

static void* (*sBytehookHookAll)(
    const char* caller_path_name,
    const char* sym_name,
    void* new_func,
    bytehook_hooked_t hooked,
    void* hooked_arg
) = NULL;
static bool sHooksInstalled = false;

static void* sPrimaryWindow = NULL;
static int sPrimaryWindowRefs = 0;
static bool sPrimaryWindowHasOpenGL = false;
static void* sReplacedVulkanWindow = NULL;
static __thread int sInCreateWindow = 0;
static __thread void* sdlLastEventWindow = NULL;

static SDL_LoadObject_t sRealSdlLoadObject = NULL;
static SDL_LoadFunction_t sRealSdlLoadFunction = NULL;
static SDL_UnloadObject_t sRealSdlUnloadObject = NULL;
static SDL_EGL_GetProcAddress_t sRealSdlEglGetProcAddress = NULL;
static SDL_GL_GetProcAddress_t sRealSdlGlGetProcAddress = NULL;
static SDL_GL_SetAttribute_t sRealSdlGlSetAttribute = NULL;
static SDL_GL_GetAttribute_t sRealSdlGlGetAttribute = NULL;
static SDL_CreateWindow_t sRealSdlCreateWindow = NULL;
static SDL_CreateWindowWithProperties_t sRealSdlCreateWindowWithProperties = NULL;
static SDL_DestroyWindow_t sRealSdlDestroyWindow = NULL;
static SDL_GetWindowFlags_t sRealSdlGetWindowFlags = NULL;
static SDL_SetWindowSize_t sRealSdlSetWindowSize = NULL;
static SDL_SetWindowTitle_t sRealSdlSetWindowTitle = NULL;
static SDL_ShowWindow_t sRealSdlShowWindow = NULL;
static SDL_HideWindow_t sRealSdlHideWindow = NULL;
static SDL_GL_MakeCurrent_t sRealSdlGlMakeCurrent = NULL;
static SDL_GetWindowFromEvent_t sRealSdlGetWindowFromEvent = NULL;
static SDL_GetWindowFromID_t sRealSdlGetWindowFromID = NULL;
static SDL_PollEvent_t sRealSdlPollEvent = NULL;
static SDL_WaitEvent_t sRealSdlWaitEvent = NULL;
static SDL_WaitEventTimeout_t sRealSdlWaitEventTimeout = NULL;
static SDL_ShowSimpleMessageBox_t sRealSdlShowSimpleMessageBox = NULL;
static SDL_ShowMessageBox_t sRealSdlShowMessageBox = NULL;
static SDL_GetNumberProperty_t sRealSdlGetNumberProperty = NULL;
static SDL_SetNumberProperty_t sRealSdlSetNumberProperty = NULL;
static SDL_GetBooleanProperty_t sRealSdlGetBooleanProperty = NULL;
static SDL_SetBooleanProperty_t sRealSdlSetBooleanProperty = NULL;
static SDL_GetStringProperty_t sRealSdlGetStringProperty = NULL;

static EglChooseConfig_t sRealEglChooseConfig = NULL;
static EglCreateContext_t sRealEglCreateContext = NULL;
static EglCreateWindowSurface_t sRealEglCreateWindowSurface = NULL;
static EglSwapBuffers_t sRealEglSwapBuffers = NULL;
static EglGetError_t sRealEglGetError = NULL;

static void* getSdlHandle(void) {
    static void* handle = NULL;
    if (handle == NULL) {
        handle = dlopen("libSDL3.so", RTLD_LAZY | RTLD_NOLOAD);
        if (handle == NULL) {
            handle = dlopen("libSDL3.so", RTLD_LAZY | RTLD_LOCAL);
        }
    }
    return handle;
}

static void resolveSdlHelperSymbols(void) {
    void* sdl = getSdlHandle();
    if (sdl == NULL) return;
    if (sRealSdlGlSetAttribute == NULL) {
        sRealSdlGlSetAttribute = (SDL_GL_SetAttribute_t) dlsym(sdl, "SDL_GL_SetAttribute");
    }
    if (sRealSdlGetWindowFlags == NULL) {
        sRealSdlGetWindowFlags = (SDL_GetWindowFlags_t) dlsym(sdl, "SDL_GetWindowFlags");
    }
    if (sRealSdlSetWindowTitle == NULL) {
        sRealSdlSetWindowTitle = (SDL_SetWindowTitle_t) dlsym(sdl, "SDL_SetWindowTitle");
    }
    if (sRealSdlShowWindow == NULL) {
        sRealSdlShowWindow = (SDL_ShowWindow_t) dlsym(sdl, "SDL_ShowWindow");
    }
    if (sRealSdlDestroyWindow == NULL) {
        sRealSdlDestroyWindow = (SDL_DestroyWindow_t) dlsym(sdl, "SDL_DestroyWindow");
    }
    if (sRealSdlGetNumberProperty == NULL) {
        sRealSdlGetNumberProperty = (SDL_GetNumberProperty_t) dlsym(sdl, "SDL_GetNumberProperty");
    }
    if (sRealSdlSetNumberProperty == NULL) {
        sRealSdlSetNumberProperty = (SDL_SetNumberProperty_t) dlsym(sdl, "SDL_SetNumberProperty");
    }
    if (sRealSdlGetBooleanProperty == NULL) {
        sRealSdlGetBooleanProperty = (SDL_GetBooleanProperty_t) dlsym(sdl, "SDL_GetBooleanProperty");
    }
    if (sRealSdlSetBooleanProperty == NULL) {
        sRealSdlSetBooleanProperty = (SDL_SetBooleanProperty_t) dlsym(sdl, "SDL_SetBooleanProperty");
    }
    if (sRealSdlGetStringProperty == NULL) {
        sRealSdlGetStringProperty = (SDL_GetStringProperty_t) dlsym(sdl, "SDL_GetStringProperty");
    }
}

static bool sdlWindowReuseEnabled(void) {
    static int cached = -1;
    if (cached >= 0) return cached == 1;
    const char* env = getenv("POJAV_SDL_REUSE_WINDOW");
    cached = (env == NULL || env[0] == '\0' || strcmp(env, "0") != 0) ? 1 : 0;
    return cached == 1;
}

static bool sdlForceOpenGlBackend(void) {
    const char* backend = getenv("POJAV_GRAPHICS_BACKEND");
    if (backend == NULL || backend[0] == '\0') {
        return false;
    }
    return strcasecmp(backend, "opengl") == 0;
}

static bool sdlGlesCompatEnabled(void) {
    static int cached = -1;
    if (cached >= 0) return cached == 1;
    const char* glLib = getenv("SDL_OPENGL_LIBRARY");
    if (glLib != NULL && strstr(glLib, "gl4es_114") != NULL) {
        cached = 0;
        return false;
    }
    cached = 1;
    return true;
}

static int proxyEglChooseConfig(void* dpy, const int* attrib_list, void** configs, int config_size, int* num_config) {
    if (sRealEglChooseConfig == NULL) return 0;

    int localCount = 0;
    int* outCount = num_config != NULL ? num_config : &localCount;

    int norm[128];
    int n = 0;
    int renderableIndex = -1;
    if (attrib_list != NULL) {
        for (int i = 0; attrib_list[i] != EGL_NONE && n < 124; i += 2) {
            norm[n] = attrib_list[i];
            norm[n + 1] = attrib_list[i + 1];
            if (attrib_list[i] == EGL_RENDERABLE_TYPE) {
                if ((attrib_list[i + 1] & EGL_OPENGL_BIT) != 0) {
                    norm[n + 1] = (attrib_list[i + 1] & ~(EGL_OPENGL_BIT | EGL_OPENGL_ES_BIT)) | EGL_OPENGL_ES3_BIT_KHR;
                }
                renderableIndex = n + 1;
            }
            n += 2;
        }
    }
    norm[n] = EGL_NONE;

    int ok = sRealEglChooseConfig(dpy, attrib_list != NULL ? norm : NULL, configs, config_size, outCount);
    if (ok && *outCount > 0) {
        return ok;
    }

    if (renderableIndex >= 0) {
        norm[renderableIndex] = (norm[renderableIndex] & ~(EGL_OPENGL_BIT | EGL_OPENGL_ES_BIT | EGL_OPENGL_ES3_BIT_KHR)) | EGL_OPENGL_ES2_BIT;
    } else if (n < 124) {
        norm[n++] = EGL_RENDERABLE_TYPE;
        norm[n++] = EGL_OPENGL_ES2_BIT;
        norm[n] = EGL_NONE;
    }
    *outCount = 0;
    return sRealEglChooseConfig(dpy, norm, configs, config_size, outCount);
}

static void* proxyEglCreateContext(void* dpy, void* config, void* share_context, const int* attrib_list) {
    if (sRealEglCreateContext == NULL) return NULL;
    if (!sdlGlesCompatEnabled()) {
        return sRealEglCreateContext(dpy, config, share_context, attrib_list);
    }

    int base[64];
    int n = 0;
    if (attrib_list != NULL) {
        for (int i = 0; attrib_list[i] != EGL_NONE && n < 56; i += 2) {
            int k = attrib_list[i];
            if (k == EGL_CONTEXT_CLIENT_VERSION ||
                k == EGL_CONTEXT_MINOR_VERSION_KHR ||
                k == EGL_CONTEXT_FLAGS_KHR ||
                k == EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR ||
                k == EGL_CONTEXT_OPENGL_NO_ERROR_KHR) {
                continue;
            }
            base[n++] = k;
            base[n++] = attrib_list[i + 1];
        }
    }

    // Try OpenGL ES 3.0 first, then pure ES 2.0 attribute fallback for strict EGL 1.4 drivers.
    int es3[64];
    memcpy(es3, base, (size_t) n * sizeof(int));
    es3[n] = EGL_CONTEXT_CLIENT_VERSION;
    es3[n + 1] = 3;
    es3[n + 2] = EGL_NONE;
    void* ctx = sRealEglCreateContext(dpy, config, share_context, es3);
    if (ctx != NULL) {
        return ctx;
    }

    es3[n + 1] = 2;
    ctx = sRealEglCreateContext(dpy, config, share_context, es3);
    if (ctx == NULL) {
        int err = sRealEglGetError != NULL ? sRealEglGetError() : -1;
        __android_log_print(ANDROID_LOG_ERROR, TAG, "eglCreateContext failed after ES3/ES2 normalization, err=0x%x", err);
    }
    return ctx;
}

static void* proxyEglCreateWindowSurface(void* dpy, void* config, void* win, const int* attrib_list) {
    if (sRealEglCreateWindowSurface == NULL) return NULL;
    if (attrib_list == NULL) {
        return sRealEglCreateWindowSurface(dpy, config, win, NULL);
    }
    int clean[64];
    int n = 0;
    for (int i = 0; attrib_list[i] != EGL_NONE && n < 60; i += 2) {
        if (attrib_list[i] == EGL_GL_COLORSPACE_KHR) continue;
        clean[n++] = attrib_list[i];
        clean[n++] = attrib_list[i + 1];
    }
    clean[n] = EGL_NONE;
    void* surface = sRealEglCreateWindowSurface(dpy, config, win, clean);
    if (surface == NULL && n > 0) {
        surface = sRealEglCreateWindowSurface(dpy, config, win, NULL);
    }
    return surface;
}

static unsigned int proxyEglSwapBuffers(void* dpy, void* surface) {
    return sRealEglSwapBuffers != NULL ? sRealEglSwapBuffers(dpy, surface) : 0;
}

static SDL_FunctionPointer wrapEglSymbolIfNeeded(const char* name, SDL_FunctionPointer realSym) {
    if (name == NULL || realSym == NULL) return realSym;
    if (strcmp(name, "eglGetError") == 0) {
        sRealEglGetError = (EglGetError_t) realSym;
        return realSym;
    }
    if (strcmp(name, "eglSwapBuffers") == 0) {
        if ((void*) realSym != (void*) &proxyEglSwapBuffers) {
            sRealEglSwapBuffers = (EglSwapBuffers_t) realSym;
        }
        return (SDL_FunctionPointer) &proxyEglSwapBuffers;
    }
    if (!sdlGlesCompatEnabled()) return realSym;
    if (strcmp(name, "eglChooseConfig") == 0) {
        if ((void*) realSym != (void*) &proxyEglChooseConfig) {
            sRealEglChooseConfig = (EglChooseConfig_t) realSym;
        }
        return (SDL_FunctionPointer) &proxyEglChooseConfig;
    }
    if (strcmp(name, "eglCreateContext") == 0) {
        if ((void*) realSym != (void*) &proxyEglCreateContext) {
            sRealEglCreateContext = (EglCreateContext_t) realSym;
        }
        return (SDL_FunctionPointer) &proxyEglCreateContext;
    }
    if (strcmp(name, "eglCreateWindowSurface") == 0) {
        if ((void*) realSym != (void*) &proxyEglCreateWindowSurface) {
            sRealEglCreateWindowSurface = (EglCreateWindowSurface_t) realSym;
        }
        return (SDL_FunctionPointer) &proxyEglCreateWindowSurface;
    }
    return realSym;
}

static int normalizeGlSetAttributeValue(int attr, int value) {
    if (!sdlGlesCompatEnabled()) return value;
    switch (attr) {
        case SDL_GL_CONTEXT_PROFILE_MASK:
            return SDL_GL_CONTEXT_PROFILE_ES;
        case SDL_GL_CONTEXT_FLAGS:
            return value & ~SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG;
        case SDL_GL_CONTEXT_MAJOR_VERSION:
            return value < 3 ? 3 : value;
        case SDL_GL_CONTEXT_MINOR_VERSION:
            return value < 3 ? 3 : value;
        default:
            return value;
    }
}

static bool normalizeGlGetAttributeResult(int attr, int* value, bool ok) {
    if (!sdlGlesCompatEnabled() || value == NULL) return ok;
    switch (attr) {
        case SDL_GL_CONTEXT_MAJOR_VERSION:
            if (!ok || *value < 3) {
                *value = 3;
            }
            return true;
        case SDL_GL_CONTEXT_MINOR_VERSION:
            if (!ok || *value < 3) {
                *value = 3;
            }
            return true;
        case SDL_GL_CONTEXT_PROFILE_MASK:
            *value = SDL_GL_CONTEXT_PROFILE_CORE;
            return true;
        default:
            return ok;
    }
}

static void prepareGlesBeforeCreateWindow(void) {
    if (!sdlGlesCompatEnabled()) return;
    setenv("SDL_OPENGL_FORCE_SRGB_FRAMEBUFFER", "0", 1);
    resolveSdlHelperSymbols();
    if (sRealSdlGlSetAttribute != NULL) {
        sRealSdlGlSetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
        sRealSdlGlSetAttribute(SDL_GL_CONTEXT_FLAGS, 0);
        sRealSdlGlSetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
        sRealSdlGlSetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 3);
    }
}

static bool handleShowSimpleMessageBox(uint32_t flags, const char* title, const char* message, void* window) {
    (void) flags;
    (void) window;
    const char* safeTitle = title != NULL ? title : "Minecraft";
    const char* safeMessage = message != NULL ? message : "<no message>";
    __android_log_print(ANDROID_LOG_ERROR, TAG, "[SDL MessageBox] %s: %s", safeTitle, safeMessage);
    fprintf(stderr, "[SDL MessageBox] %s: %s\n", safeTitle, safeMessage);
    fflush(stderr);
    return true;
}

static bool handleShowMessageBox(const void* messageboxdata, int* buttonid) {
    if (buttonid != NULL) {
        *buttonid = 0;
    }
    const char* safeTitle = "Minecraft";
    const char* safeMessage = "<no message>";
    if (messageboxdata != NULL) {
        const SDL_MessageBoxData_Layout* data = (const SDL_MessageBoxData_Layout*) messageboxdata;
        if (data->title != NULL) safeTitle = data->title;
        if (data->message != NULL) safeMessage = data->message;
    }
    __android_log_print(ANDROID_LOG_ERROR, TAG, "[SDL MessageBox] %s: %s", safeTitle, safeMessage);
    fprintf(stderr, "[SDL MessageBox] %s: %s\n", safeTitle, safeMessage);
    fflush(stderr);
    return true;
}

static bool windowActuallyHasOpenGL(void* window, uint64_t requestedFlags) {
    resolveSdlHelperSymbols();
    if (window != NULL && sRealSdlGetWindowFlags != NULL) {
        uint64_t actual = sRealSdlGetWindowFlags(window);
        if ((actual & SDL_WINDOW_OPENGL) != 0) return true;
        if ((actual & SDL_WINDOW_VULKAN) != 0) return false;
    }
    return (requestedFlags & SDL_WINDOW_OPENGL) != 0 && (requestedFlags & SDL_WINDOW_VULKAN) == 0;
}

static void replaceNonOpenGlPrimaryWindowIfNeeded(bool requestsOpenGL, const char* caller) {
    if (sPrimaryWindow == NULL || sPrimaryWindowHasOpenGL || !requestsOpenGL) {
        return;
    }
    resolveSdlHelperSymbols();
    void* oldWindow = sPrimaryWindow;
    sReplacedVulkanWindow = oldWindow;
    sPrimaryWindow = NULL;
    sPrimaryWindowRefs = 0;
    sPrimaryWindowHasOpenGL = false;
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "%s replacing non-OpenGL primary window %p before creating OpenGL window",
        caller,
        oldWindow
    );
    if (sRealSdlDestroyWindow != NULL) {
        sRealSdlDestroyWindow(oldWindow);
    }
}

static void* reusePrimaryWindow(const char* caller, const char* title, bool hidden) {
    resolveSdlHelperSymbols();
    sPrimaryWindowRefs++;
    if (title != NULL && title[0] != '\0' && sRealSdlSetWindowTitle != NULL) {
        sRealSdlSetWindowTitle(sPrimaryWindow, title);
    }
    if (!hidden && sRealSdlShowWindow != NULL) {
        sRealSdlShowWindow(sPrimaryWindow);
    }
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "%s reusing primary SDL_Window=%p (refs=%d, hasGL=%d, hidden=%d)",
        caller,
        sPrimaryWindow,
        sPrimaryWindowRefs,
        sPrimaryWindowHasOpenGL ? 1 : 0,
        hidden ? 1 : 0
    );
    return sPrimaryWindow;
}

static void* handleSdlCreateWindow(
    SDL_CreateWindow_t realFn,
    const char* title,
    int w,
    int h,
    uint64_t flags
) {
    if (sdlForceOpenGlBackend()) {
        flags = (flags & ~SDL_WINDOW_VULKAN) | SDL_WINDOW_OPENGL;
    }
    bool requestsOpenGL = (flags & SDL_WINDOW_OPENGL) != 0 && (flags & SDL_WINDOW_VULKAN) == 0;
    bool hidden = (flags & SDL_WINDOW_HIDDEN) != 0;

    if (sdlWindowReuseEnabled()) {
        replaceNonOpenGlPrimaryWindowIfNeeded(requestsOpenGL, "SDL_CreateWindow");
        if (sPrimaryWindow != NULL) {
            return reusePrimaryWindow("SDL_CreateWindow", title, hidden);
        }
    }

    if (requestsOpenGL) {
        prepareGlesBeforeCreateWindow();
    }

    sInCreateWindow++;
    void* window = realFn != NULL ? realFn(title, w, h, flags) : NULL;
    sInCreateWindow--;

    if (sdlWindowReuseEnabled() && window != NULL && sPrimaryWindow == NULL) {
        sPrimaryWindow = window;
        sPrimaryWindowRefs = 1;
        sPrimaryWindowHasOpenGL = windowActuallyHasOpenGL(window, flags);
        if (sReplacedVulkanWindow == window) {
            sReplacedVulkanWindow = NULL;
        }
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "SDL_CreateWindow captured primary window=%p (refs=1, hasGL=%d, flags=0x%llx)",
            window,
            sPrimaryWindowHasOpenGL ? 1 : 0,
            (unsigned long long) flags
        );
    }
    return window;
}

static void* handleSdlCreateWindowWithProperties(
    SDL_CreateWindowWithProperties_t realFn,
    uint32_t props
) {
    if (sInCreateWindow > 0) {
        return realFn != NULL ? realFn(props) : NULL;
    }

    resolveSdlHelperSymbols();
    uint64_t flags = sRealSdlGetNumberProperty != NULL
        ? (uint64_t) sRealSdlGetNumberProperty(props, SDL_PROP_WINDOW_CREATE_FLAGS_NUMBER, 0)
        : 0ULL;
    bool propOpenGL = sRealSdlGetBooleanProperty != NULL &&
        sRealSdlGetBooleanProperty(props, SDL_PROP_WINDOW_CREATE_OPENGL_BOOLEAN, (flags & SDL_WINDOW_OPENGL) != 0);
    bool propVulkan = sRealSdlGetBooleanProperty != NULL &&
        sRealSdlGetBooleanProperty(props, SDL_PROP_WINDOW_CREATE_VULKAN_BOOLEAN, (flags & SDL_WINDOW_VULKAN) != 0);
    bool propHidden = sRealSdlGetBooleanProperty != NULL &&
        sRealSdlGetBooleanProperty(props, SDL_PROP_WINDOW_CREATE_HIDDEN_BOOLEAN, (flags & SDL_WINDOW_HIDDEN) != 0);
    const char* title = sRealSdlGetStringProperty != NULL
        ? sRealSdlGetStringProperty(props, SDL_PROP_WINDOW_CREATE_TITLE_STRING, NULL)
        : NULL;

    if (sdlForceOpenGlBackend()) {
        flags = (flags & ~SDL_WINDOW_VULKAN) | SDL_WINDOW_OPENGL;
        propOpenGL = true;
        propVulkan = false;
        if (sRealSdlSetNumberProperty != NULL) {
            sRealSdlSetNumberProperty(props, SDL_PROP_WINDOW_CREATE_FLAGS_NUMBER, (int64_t) flags);
        }
        if (sRealSdlSetBooleanProperty != NULL) {
            sRealSdlSetBooleanProperty(props, SDL_PROP_WINDOW_CREATE_OPENGL_BOOLEAN, true);
            sRealSdlSetBooleanProperty(props, SDL_PROP_WINDOW_CREATE_VULKAN_BOOLEAN, false);
        }
    }

    bool requestsOpenGL = (propOpenGL || (flags & SDL_WINDOW_OPENGL) != 0) && !propVulkan && (flags & SDL_WINDOW_VULKAN) == 0;
    if (sdlWindowReuseEnabled()) {
        replaceNonOpenGlPrimaryWindowIfNeeded(requestsOpenGL, "SDL_CreateWindowWithProperties");
        if (sPrimaryWindow != NULL) {
            return reusePrimaryWindow("SDL_CreateWindowWithProperties", title, propHidden || (flags & SDL_WINDOW_HIDDEN) != 0);
        }
    }

    if (requestsOpenGL) {
        prepareGlesBeforeCreateWindow();
    }

    sInCreateWindow++;
    void* window = realFn != NULL ? realFn(props) : NULL;
    sInCreateWindow--;

    if (sdlWindowReuseEnabled() && window != NULL && sPrimaryWindow == NULL) {
        sPrimaryWindow = window;
        sPrimaryWindowRefs = 1;
        sPrimaryWindowHasOpenGL = windowActuallyHasOpenGL(window, requestsOpenGL ? SDL_WINDOW_OPENGL : flags);
        if (sReplacedVulkanWindow == window) {
            sReplacedVulkanWindow = NULL;
        }
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "SDL_CreateWindowWithProperties captured primary window=%p (refs=1, hasGL=%d)",
            window,
            sPrimaryWindowHasOpenGL ? 1 : 0
        );
    }
    return window;
}

static bool shouldSkipDestroyWindow(void* window) {
    if (window == NULL) return true;

    if (window == sReplacedVulkanWindow && window != sPrimaryWindow) {
        sReplacedVulkanWindow = NULL;
        return true;
    }

    if (sdlWindowReuseEnabled() && window == sPrimaryWindow) {
        if (sPrimaryWindowRefs > 1) {
            sPrimaryWindowRefs--;
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "SDL_DestroyWindow suppressed for shared primary window=%p (remaining refs=%d)",
                window,
                sPrimaryWindowRefs
            );
            return true;
        }
        if (sPrimaryWindowHasOpenGL) {
            sPrimaryWindowRefs = 0;
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "SDL_DestroyWindow pinned OpenGL primary window=%p for single-window reuse",
                window
            );
            return true;
        }
        sPrimaryWindow = NULL;
        sPrimaryWindowRefs = 0;
        sPrimaryWindowHasOpenGL = false;
    }
    return false;
}

static void* resolveEventWindowFallback(void* win) {
    if (win != NULL) {
        sdlLastEventWindow = win;
        return win;
    }
    if (sdlWindowReuseEnabled()) {
        if (sdlLastEventWindow != NULL) return sdlLastEventWindow;
        if (sPrimaryWindow != NULL) return sPrimaryWindow;
    }
    return NULL;
}

static void rememberEventWindow(const void* event) {
    if (event == NULL) return;
    SDL_GetWindowFromEvent_t fn = sRealSdlGetWindowFromEvent;
    if (fn != NULL) {
        void* win = fn(event);
        if (win != NULL) {
            sdlLastEventWindow = win;
            return;
        }
    }
    if (sdlLastEventWindow == NULL && sPrimaryWindow != NULL) {
        sdlLastEventWindow = sPrimaryWindow;
    }
}

// --- dlsym / ndlsym function-pointer proxies (do not touch bytehook stack) ---

static bool proxySdlGlSetAttribute(int attr, int value) {
    int normalized = normalizeGlSetAttributeValue(attr, value);
    return sRealSdlGlSetAttribute != NULL ? sRealSdlGlSetAttribute(attr, normalized) : false;
}

static bool proxySdlGlGetAttribute(int attr, int* value) {
    bool ok = sRealSdlGlGetAttribute != NULL ? sRealSdlGlGetAttribute(attr, value) : false;
    return normalizeGlGetAttributeResult(attr, value, ok);
}

static bool proxySdlShowSimpleMessageBox(uint32_t flags, const char* title, const char* message, void* window) {
    return handleShowSimpleMessageBox(flags, title, message, window);
}

static bool proxySdlShowMessageBox(const void* messageboxdata, int* buttonid) {
    return handleShowMessageBox(messageboxdata, buttonid);
}

static void* proxySdlLoadObject(const char* sofile) {
    if (sofile != NULL && strstr(sofile, "libvulkan") != NULL) {
        return VULKAN_PTR;
    }
    return sRealSdlLoadObject != NULL ? sRealSdlLoadObject(sofile) : NULL;
}

static SDL_FunctionPointer proxySdlLoadFunction(void* handle, const char* name) {
    SDL_FunctionPointer realSym = sRealSdlLoadFunction != NULL ? sRealSdlLoadFunction(handle, name) : NULL;
    return wrapEglSymbolIfNeeded(name, realSym);
}

static void proxySdlUnloadObject(void* handle) {
    if (handle == VULKAN_PTR) return;
    if (sRealSdlUnloadObject != NULL) sRealSdlUnloadObject(handle);
}

static SDL_FunctionPointer proxySdlEglGetProcAddress(const char* proc) {
    SDL_FunctionPointer realSym = sRealSdlEglGetProcAddress != NULL ? sRealSdlEglGetProcAddress(proc) : NULL;
    return wrapEglSymbolIfNeeded(proc, realSym);
}

static SDL_FunctionPointer proxySdlGlGetProcAddress(const char* proc) {
    return sRealSdlGlGetProcAddress != NULL ? sRealSdlGlGetProcAddress(proc) : NULL;
}

static void* proxySdlCreateWindow(const char* title, int w, int h, uint64_t flags) {
    return handleSdlCreateWindow(sRealSdlCreateWindow, title, w, h, flags);
}

static void* proxySdlCreateWindowWithProperties(uint32_t props) {
    return handleSdlCreateWindowWithProperties(sRealSdlCreateWindowWithProperties, props);
}

static void proxySdlDestroyWindow(void* window) {
    if (shouldSkipDestroyWindow(window)) return;
    if (sRealSdlDestroyWindow != NULL) sRealSdlDestroyWindow(window);
}

static bool proxySdlSetWindowSize(void* window, int w, int h) {
    if (window == NULL || w <= 0 || h <= 0) {
        return sRealSdlSetWindowSize != NULL ? sRealSdlSetWindowSize(window, w, h) : false;
    }
    if (sRealSdlSetWindowSize != NULL) {
        (void) sRealSdlSetWindowSize(window, w, h);
    }
    return true;
}

static bool proxySdlHideWindow(void* window) {
    if (sdlWindowReuseEnabled() && window == sPrimaryWindow && sPrimaryWindowRefs > 1) {
        return true;
    }
    return sRealSdlHideWindow != NULL ? sRealSdlHideWindow(window) : true;
}

static bool proxySdlGlMakeCurrent(void* window, void* context) {
    if (sRealSdlGlMakeCurrent == NULL) return false;
    if (window == NULL && context != NULL && sPrimaryWindow != NULL) {
        if (sRealSdlGlMakeCurrent(NULL, context)) return true;
        return sRealSdlGlMakeCurrent(sPrimaryWindow, context);
    }
    return sRealSdlGlMakeCurrent(window, context);
}

static void* proxySdlGetWindowFromEvent(const void* event) {
    void* win = sRealSdlGetWindowFromEvent != NULL ? sRealSdlGetWindowFromEvent(event) : NULL;
    return resolveEventWindowFallback(win);
}

static void* proxySdlGetWindowFromID(uint32_t id) {
    void* win = sRealSdlGetWindowFromID != NULL ? sRealSdlGetWindowFromID(id) : NULL;
    return resolveEventWindowFallback(win);
}

static bool proxySdlPollEvent(void* event) {
    bool ok = sRealSdlPollEvent != NULL ? sRealSdlPollEvent(event) : false;
    if (ok) rememberEventWindow(event);
    return ok;
}

static bool proxySdlWaitEvent(void* event) {
    bool ok = sRealSdlWaitEvent != NULL ? sRealSdlWaitEvent(event) : false;
    if (ok) rememberEventWindow(event);
    return ok;
}

static bool proxySdlWaitEventTimeout(void* event, int32_t timeoutMS) {
    bool ok = sRealSdlWaitEventTimeout != NULL ? sRealSdlWaitEventTimeout(event, timeoutMS) : false;
    if (ok) rememberEventWindow(event);
    return ok;
}

// --- bytehook PLT wrappers (use BYTEHOOK_CALL_PREV + BYTEHOOK_POP_STACK) ---

static bool custom_SDL_GL_SetAttribute_Func(int attr, int value) {
    int normalized = normalizeGlSetAttributeValue(attr, value);
    bool res = BYTEHOOK_CALL_PREV(custom_SDL_GL_SetAttribute_Func, SDL_GL_SetAttribute_t, attr, normalized);
    BYTEHOOK_POP_STACK();
    return res;
}

static bool custom_SDL_GL_GetAttribute_Func(int attr, int* value) {
    bool ok = BYTEHOOK_CALL_PREV(custom_SDL_GL_GetAttribute_Func, SDL_GL_GetAttribute_t, attr, value);
    BYTEHOOK_POP_STACK();
    return normalizeGlGetAttributeResult(attr, value, ok);
}

static bool custom_SDL_ShowSimpleMessageBox_Func(uint32_t flags, const char* title, const char* message, void* window) {
    bool res = handleShowSimpleMessageBox(flags, title, message, window);
    BYTEHOOK_POP_STACK();
    return res;
}

static bool custom_SDL_ShowMessageBox_Func(const void* messageboxdata, int* buttonid) {
    bool res = handleShowMessageBox(messageboxdata, buttonid);
    BYTEHOOK_POP_STACK();
    return res;
}

static void* custom_SDL_LoadObject_Func(const char* sofile) {
    if (sofile != NULL && strstr(sofile, "libvulkan") != NULL) {
        BYTEHOOK_POP_STACK();
        return VULKAN_PTR;
    }
    void* res = BYTEHOOK_CALL_PREV(custom_SDL_LoadObject_Func, SDL_LoadObject_t, sofile);
    BYTEHOOK_POP_STACK();
    return res;
}

static SDL_FunctionPointer custom_SDL_LoadFunction_Func(void* handle, const char* name) {
    SDL_FunctionPointer realSym = BYTEHOOK_CALL_PREV(custom_SDL_LoadFunction_Func, SDL_LoadFunction_t, handle, name);
    BYTEHOOK_POP_STACK();
    return wrapEglSymbolIfNeeded(name, realSym);
}

static void custom_SDL_UnloadObject_Func(void* handle) {
    if (handle != VULKAN_PTR) {
        BYTEHOOK_CALL_PREV(custom_SDL_UnloadObject_Func, SDL_UnloadObject_t, handle);
    }
    BYTEHOOK_POP_STACK();
}

static SDL_FunctionPointer custom_SDL_EGL_GetProcAddress_Func(const char* proc) {
    SDL_FunctionPointer realSym = BYTEHOOK_CALL_PREV(custom_SDL_EGL_GetProcAddress_Func, SDL_EGL_GetProcAddress_t, proc);
    BYTEHOOK_POP_STACK();
    return wrapEglSymbolIfNeeded(proc, realSym);
}

static SDL_FunctionPointer custom_SDL_GL_GetProcAddress_Func(const char* proc) {
    SDL_FunctionPointer realSym = BYTEHOOK_CALL_PREV(custom_SDL_GL_GetProcAddress_Func, SDL_GL_GetProcAddress_t, proc);
    BYTEHOOK_POP_STACK();
    return realSym;
}

static void* custom_SDL_CreateWindow_Func(const char* title, int w, int h, uint64_t flags) {
    if (sdlForceOpenGlBackend()) {
        flags = (flags & ~SDL_WINDOW_VULKAN) | SDL_WINDOW_OPENGL;
    }
    bool requestsOpenGL = (flags & SDL_WINDOW_OPENGL) != 0 && (flags & SDL_WINDOW_VULKAN) == 0;
    bool hidden = (flags & SDL_WINDOW_HIDDEN) != 0;

    if (sdlWindowReuseEnabled()) {
        replaceNonOpenGlPrimaryWindowIfNeeded(requestsOpenGL, "SDL_CreateWindow");
        if (sPrimaryWindow != NULL) {
            void* reused = reusePrimaryWindow("SDL_CreateWindow", title, hidden);
            BYTEHOOK_POP_STACK();
            return reused;
        }
    }

    if (requestsOpenGL) {
        prepareGlesBeforeCreateWindow();
    }

    sInCreateWindow++;
    void* window = BYTEHOOK_CALL_PREV(custom_SDL_CreateWindow_Func, SDL_CreateWindow_t, title, w, h, flags);
    sInCreateWindow--;

    if (sdlWindowReuseEnabled() && window != NULL && sPrimaryWindow == NULL) {
        sPrimaryWindow = window;
        sPrimaryWindowRefs = 1;
        sPrimaryWindowHasOpenGL = windowActuallyHasOpenGL(window, flags);
        if (sReplacedVulkanWindow == window) {
            sReplacedVulkanWindow = NULL;
        }
    }
    BYTEHOOK_POP_STACK();
    return window;
}

static void* custom_SDL_CreateWindowWithProperties_Func(uint32_t props) {
    if (sInCreateWindow > 0) {
        void* inner = BYTEHOOK_CALL_PREV(custom_SDL_CreateWindowWithProperties_Func, SDL_CreateWindowWithProperties_t, props);
        BYTEHOOK_POP_STACK();
        return inner;
    }

    resolveSdlHelperSymbols();
    uint64_t flags = sRealSdlGetNumberProperty != NULL
        ? (uint64_t) sRealSdlGetNumberProperty(props, SDL_PROP_WINDOW_CREATE_FLAGS_NUMBER, 0)
        : 0ULL;
    bool propOpenGL = sRealSdlGetBooleanProperty != NULL &&
        sRealSdlGetBooleanProperty(props, SDL_PROP_WINDOW_CREATE_OPENGL_BOOLEAN, (flags & SDL_WINDOW_OPENGL) != 0);
    bool propVulkan = sRealSdlGetBooleanProperty != NULL &&
        sRealSdlGetBooleanProperty(props, SDL_PROP_WINDOW_CREATE_VULKAN_BOOLEAN, (flags & SDL_WINDOW_VULKAN) != 0);
    bool propHidden = sRealSdlGetBooleanProperty != NULL &&
        sRealSdlGetBooleanProperty(props, SDL_PROP_WINDOW_CREATE_HIDDEN_BOOLEAN, (flags & SDL_WINDOW_HIDDEN) != 0);
    const char* title = sRealSdlGetStringProperty != NULL
        ? sRealSdlGetStringProperty(props, SDL_PROP_WINDOW_CREATE_TITLE_STRING, NULL)
        : NULL;

    if (sdlForceOpenGlBackend()) {
        flags = (flags & ~SDL_WINDOW_VULKAN) | SDL_WINDOW_OPENGL;
        propOpenGL = true;
        propVulkan = false;
        if (sRealSdlSetNumberProperty != NULL) {
            sRealSdlSetNumberProperty(props, SDL_PROP_WINDOW_CREATE_FLAGS_NUMBER, (int64_t) flags);
        }
        if (sRealSdlSetBooleanProperty != NULL) {
            sRealSdlSetBooleanProperty(props, SDL_PROP_WINDOW_CREATE_OPENGL_BOOLEAN, true);
            sRealSdlSetBooleanProperty(props, SDL_PROP_WINDOW_CREATE_VULKAN_BOOLEAN, false);
        }
    }

    bool requestsOpenGL = (propOpenGL || (flags & SDL_WINDOW_OPENGL) != 0) && !propVulkan && (flags & SDL_WINDOW_VULKAN) == 0;
    if (sdlWindowReuseEnabled()) {
        replaceNonOpenGlPrimaryWindowIfNeeded(requestsOpenGL, "SDL_CreateWindowWithProperties");
        if (sPrimaryWindow != NULL) {
            void* reused = reusePrimaryWindow("SDL_CreateWindowWithProperties", title, propHidden || (flags & SDL_WINDOW_HIDDEN) != 0);
            BYTEHOOK_POP_STACK();
            return reused;
        }
    }

    if (requestsOpenGL) {
        prepareGlesBeforeCreateWindow();
    }

    sInCreateWindow++;
    void* window = BYTEHOOK_CALL_PREV(custom_SDL_CreateWindowWithProperties_Func, SDL_CreateWindowWithProperties_t, props);
    sInCreateWindow--;

    if (sdlWindowReuseEnabled() && window != NULL && sPrimaryWindow == NULL) {
        sPrimaryWindow = window;
        sPrimaryWindowRefs = 1;
        sPrimaryWindowHasOpenGL = windowActuallyHasOpenGL(window, requestsOpenGL ? SDL_WINDOW_OPENGL : flags);
        if (sReplacedVulkanWindow == window) {
            sReplacedVulkanWindow = NULL;
        }
    }
    BYTEHOOK_POP_STACK();
    return window;
}

static void custom_SDL_DestroyWindow_Func(void* window) {
    if (!shouldSkipDestroyWindow(window)) {
        BYTEHOOK_CALL_PREV(custom_SDL_DestroyWindow_Func, SDL_DestroyWindow_t, window);
    }
    BYTEHOOK_POP_STACK();
}

static bool custom_SDL_SetWindowSize_Func(void* window, int w, int h) {
    if (window == NULL || w <= 0 || h <= 0) {
        bool res = BYTEHOOK_CALL_PREV(custom_SDL_SetWindowSize_Func, SDL_SetWindowSize_t, window, w, h);
        BYTEHOOK_POP_STACK();
        return res;
    }
    (void) BYTEHOOK_CALL_PREV(custom_SDL_SetWindowSize_Func, SDL_SetWindowSize_t, window, w, h);
    BYTEHOOK_POP_STACK();
    return true;
}

static bool custom_SDL_HideWindow_Func(void* window) {
    if (sdlWindowReuseEnabled() && window == sPrimaryWindow && sPrimaryWindowRefs > 1) {
        BYTEHOOK_POP_STACK();
        return true;
    }
    bool res = BYTEHOOK_CALL_PREV(custom_SDL_HideWindow_Func, SDL_HideWindow_t, window);
    BYTEHOOK_POP_STACK();
    return res;
}

static bool custom_SDL_GL_MakeCurrent_Func(void* window, void* context) {
    if (window == NULL && context != NULL && sPrimaryWindow != NULL) {
        bool ok = BYTEHOOK_CALL_PREV(custom_SDL_GL_MakeCurrent_Func, SDL_GL_MakeCurrent_t, NULL, context);
        if (!ok) {
            ok = BYTEHOOK_CALL_PREV(custom_SDL_GL_MakeCurrent_Func, SDL_GL_MakeCurrent_t, sPrimaryWindow, context);
        }
        BYTEHOOK_POP_STACK();
        return ok;
    }
    bool res = BYTEHOOK_CALL_PREV(custom_SDL_GL_MakeCurrent_Func, SDL_GL_MakeCurrent_t, window, context);
    BYTEHOOK_POP_STACK();
    return res;
}

static void* custom_SDL_GetWindowFromEvent_Func(const void* event) {
    void* win = BYTEHOOK_CALL_PREV(custom_SDL_GetWindowFromEvent_Func, SDL_GetWindowFromEvent_t, event);
    BYTEHOOK_POP_STACK();
    return resolveEventWindowFallback(win);
}

static void* custom_SDL_GetWindowFromID_Func(uint32_t id) {
    void* win = BYTEHOOK_CALL_PREV(custom_SDL_GetWindowFromID_Func, SDL_GetWindowFromID_t, id);
    BYTEHOOK_POP_STACK();
    return resolveEventWindowFallback(win);
}

static bool custom_SDL_PollEvent_Func(void* event) {
    bool ok = BYTEHOOK_CALL_PREV(custom_SDL_PollEvent_Func, SDL_PollEvent_t, event);
    BYTEHOOK_POP_STACK();
    if (ok) rememberEventWindow(event);
    return ok;
}

static bool custom_SDL_WaitEvent_Func(void* event) {
    bool ok = BYTEHOOK_CALL_PREV(custom_SDL_WaitEvent_Func, SDL_WaitEvent_t, event);
    BYTEHOOK_POP_STACK();
    if (ok) rememberEventWindow(event);
    return ok;
}

static bool custom_SDL_WaitEventTimeout_Func(void* event, int32_t timeoutMS) {
    bool ok = BYTEHOOK_CALL_PREV(custom_SDL_WaitEventTimeout_Func, SDL_WaitEventTimeout_t, event, timeoutMS);
    BYTEHOOK_POP_STACK();
    if (ok) rememberEventWindow(event);
    return ok;
}

__attribute__((visibility("default")))
void* sdlDlsymProxy(void* handle, const char* name, void* realPtr) {
    (void) handle;
    if (name == NULL || realPtr == NULL) return realPtr;

    if (strcmp(name, "SDL_GL_SetAttribute") == 0) {
        if (realPtr != (void*) &proxySdlGlSetAttribute && realPtr != (void*) &custom_SDL_GL_SetAttribute_Func) {
            sRealSdlGlSetAttribute = (SDL_GL_SetAttribute_t) realPtr;
        }
        return (void*) &proxySdlGlSetAttribute;
    }
    if (strcmp(name, "SDL_GL_GetAttribute") == 0) {
        if (realPtr != (void*) &proxySdlGlGetAttribute && realPtr != (void*) &custom_SDL_GL_GetAttribute_Func) {
            sRealSdlGlGetAttribute = (SDL_GL_GetAttribute_t) realPtr;
        }
        return (void*) &proxySdlGlGetAttribute;
    }
    if (strcmp(name, "SDL_ShowSimpleMessageBox") == 0) {
        if (realPtr != (void*) &proxySdlShowSimpleMessageBox && realPtr != (void*) &custom_SDL_ShowSimpleMessageBox_Func) {
            sRealSdlShowSimpleMessageBox = (SDL_ShowSimpleMessageBox_t) realPtr;
        }
        return (void*) &proxySdlShowSimpleMessageBox;
    }
    if (strcmp(name, "SDL_ShowMessageBox") == 0) {
        if (realPtr != (void*) &proxySdlShowMessageBox && realPtr != (void*) &custom_SDL_ShowMessageBox_Func) {
            sRealSdlShowMessageBox = (SDL_ShowMessageBox_t) realPtr;
        }
        return (void*) &proxySdlShowMessageBox;
    }
    if (strcmp(name, "SDL_LoadObject") == 0) {
        if (realPtr != (void*) &proxySdlLoadObject && realPtr != (void*) &custom_SDL_LoadObject_Func) {
            sRealSdlLoadObject = (SDL_LoadObject_t) realPtr;
        }
        return (void*) &proxySdlLoadObject;
    }
    if (strcmp(name, "SDL_LoadFunction") == 0) {
        if (realPtr != (void*) &proxySdlLoadFunction && realPtr != (void*) &custom_SDL_LoadFunction_Func) {
            sRealSdlLoadFunction = (SDL_LoadFunction_t) realPtr;
        }
        return (void*) &proxySdlLoadFunction;
    }
    if (strcmp(name, "SDL_UnloadObject") == 0) {
        if (realPtr != (void*) &proxySdlUnloadObject && realPtr != (void*) &custom_SDL_UnloadObject_Func) {
            sRealSdlUnloadObject = (SDL_UnloadObject_t) realPtr;
        }
        return (void*) &proxySdlUnloadObject;
    }
    if (strcmp(name, "SDL_EGL_GetProcAddress") == 0) {
        if (realPtr != (void*) &proxySdlEglGetProcAddress && realPtr != (void*) &custom_SDL_EGL_GetProcAddress_Func) {
            sRealSdlEglGetProcAddress = (SDL_EGL_GetProcAddress_t) realPtr;
        }
        return (void*) &proxySdlEglGetProcAddress;
    }
    if (strcmp(name, "SDL_GL_GetProcAddress") == 0) {
        if (realPtr != (void*) &proxySdlGlGetProcAddress && realPtr != (void*) &custom_SDL_GL_GetProcAddress_Func) {
            sRealSdlGlGetProcAddress = (SDL_GL_GetProcAddress_t) realPtr;
        }
        return (void*) &proxySdlGlGetProcAddress;
    }
    if (strcmp(name, "SDL_CreateWindow") == 0) {
        if (realPtr != (void*) &proxySdlCreateWindow && realPtr != (void*) &custom_SDL_CreateWindow_Func) {
            sRealSdlCreateWindow = (SDL_CreateWindow_t) realPtr;
        }
        return (void*) &proxySdlCreateWindow;
    }
    if (strcmp(name, "SDL_CreateWindowWithProperties") == 0) {
        if (realPtr != (void*) &proxySdlCreateWindowWithProperties &&
            realPtr != (void*) &custom_SDL_CreateWindowWithProperties_Func) {
            sRealSdlCreateWindowWithProperties = (SDL_CreateWindowWithProperties_t) realPtr;
        }
        return (void*) &proxySdlCreateWindowWithProperties;
    }
    if (strcmp(name, "SDL_DestroyWindow") == 0) {
        if (realPtr != (void*) &proxySdlDestroyWindow && realPtr != (void*) &custom_SDL_DestroyWindow_Func) {
            sRealSdlDestroyWindow = (SDL_DestroyWindow_t) realPtr;
        }
        return (void*) &proxySdlDestroyWindow;
    }
    if (strcmp(name, "SDL_SetWindowSize") == 0) {
        if (realPtr != (void*) &proxySdlSetWindowSize && realPtr != (void*) &custom_SDL_SetWindowSize_Func) {
            sRealSdlSetWindowSize = (SDL_SetWindowSize_t) realPtr;
        }
        return (void*) &proxySdlSetWindowSize;
    }
    if (strcmp(name, "SDL_HideWindow") == 0) {
        if (realPtr != (void*) &proxySdlHideWindow && realPtr != (void*) &custom_SDL_HideWindow_Func) {
            sRealSdlHideWindow = (SDL_HideWindow_t) realPtr;
        }
        return (void*) &proxySdlHideWindow;
    }
    if (strcmp(name, "SDL_GL_MakeCurrent") == 0) {
        if (realPtr != (void*) &proxySdlGlMakeCurrent && realPtr != (void*) &custom_SDL_GL_MakeCurrent_Func) {
            sRealSdlGlMakeCurrent = (SDL_GL_MakeCurrent_t) realPtr;
        }
        return (void*) &proxySdlGlMakeCurrent;
    }
    if (strcmp(name, "SDL_GetWindowFromEvent") == 0) {
        if (realPtr != (void*) &proxySdlGetWindowFromEvent && realPtr != (void*) &custom_SDL_GetWindowFromEvent_Func) {
            sRealSdlGetWindowFromEvent = (SDL_GetWindowFromEvent_t) realPtr;
        }
        return (void*) &proxySdlGetWindowFromEvent;
    }
    if (strcmp(name, "SDL_GetWindowFromID") == 0) {
        if (realPtr != (void*) &proxySdlGetWindowFromID && realPtr != (void*) &custom_SDL_GetWindowFromID_Func) {
            sRealSdlGetWindowFromID = (SDL_GetWindowFromID_t) realPtr;
        }
        return (void*) &proxySdlGetWindowFromID;
    }
    if (strcmp(name, "SDL_PollEvent") == 0) {
        if (realPtr != (void*) &proxySdlPollEvent && realPtr != (void*) &custom_SDL_PollEvent_Func) {
            sRealSdlPollEvent = (SDL_PollEvent_t) realPtr;
        }
        return (void*) &proxySdlPollEvent;
    }
    if (strcmp(name, "SDL_WaitEvent") == 0) {
        if (realPtr != (void*) &proxySdlWaitEvent && realPtr != (void*) &custom_SDL_WaitEvent_Func) {
            sRealSdlWaitEvent = (SDL_WaitEvent_t) realPtr;
        }
        return (void*) &proxySdlWaitEvent;
    }
    if (strcmp(name, "SDL_WaitEventTimeout") == 0) {
        if (realPtr != (void*) &proxySdlWaitEventTimeout && realPtr != (void*) &custom_SDL_WaitEventTimeout_Func) {
            sRealSdlWaitEventTimeout = (SDL_WaitEventTimeout_t) realPtr;
        }
        return (void*) &proxySdlWaitEventTimeout;
    }
    return realPtr;
}

void create_sdl_hooks(void* bytehook_hook_all_p) {
    if (bytehook_hook_all_p == NULL || sHooksInstalled) {
        return;
    }
    sBytehookHookAll = bytehook_hook_all_p;
    sHooksInstalled = true;

    sBytehookHookAll(NULL, "SDL_GL_SetAttribute", (void*) &custom_SDL_GL_SetAttribute_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_GL_GetAttribute", (void*) &custom_SDL_GL_GetAttribute_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_ShowSimpleMessageBox", (void*) &custom_SDL_ShowSimpleMessageBox_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_ShowMessageBox", (void*) &custom_SDL_ShowMessageBox_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_LoadObject", (void*) &custom_SDL_LoadObject_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_LoadFunction", (void*) &custom_SDL_LoadFunction_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_UnloadObject", (void*) &custom_SDL_UnloadObject_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_EGL_GetProcAddress", (void*) &custom_SDL_EGL_GetProcAddress_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_GL_GetProcAddress", (void*) &custom_SDL_GL_GetProcAddress_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_CreateWindow", (void*) &custom_SDL_CreateWindow_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_CreateWindowWithProperties", (void*) &custom_SDL_CreateWindowWithProperties_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_DestroyWindow", (void*) &custom_SDL_DestroyWindow_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_SetWindowSize", (void*) &custom_SDL_SetWindowSize_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_HideWindow", (void*) &custom_SDL_HideWindow_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_GL_MakeCurrent", (void*) &custom_SDL_GL_MakeCurrent_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_GetWindowFromEvent", (void*) &custom_SDL_GetWindowFromEvent_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_GetWindowFromID", (void*) &custom_SDL_GetWindowFromID_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_PollEvent", (void*) &custom_SDL_PollEvent_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_WaitEvent", (void*) &custom_SDL_WaitEvent_Func, NULL, NULL);
    sBytehookHookAll(NULL, "SDL_WaitEventTimeout", (void*) &custom_SDL_WaitEventTimeout_Func, NULL, NULL);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Installed SDL3 single-window and OpenGL ES compatibility hooks");
}

#include "sdl_hook.h"

#include <assert.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int __android_log_print(int priority, const char* tag, const char* fmt, ...) {
    (void) priority;
    (void) tag;
    (void) fmt;
    return 0;
}

void* bytehook_get_prev_func(void* func) {
    (void) func;
    return NULL;
}

void bytehook_pop_stack(void* return_address) {
    (void) return_address;
}

#define SDL_WINDOW_OPENGL 0x0000000000000002ULL
#define SDL_WINDOW_HIDDEN 0x0000000000000008ULL
#define SDL_WINDOW_VULKAN 0x0000000010000000ULL

#define SDL_GL_CONTEXT_MAJOR_VERSION 17
#define SDL_GL_CONTEXT_MINOR_VERSION 18
#define SDL_GL_CONTEXT_FLAGS 20
#define SDL_GL_CONTEXT_PROFILE_MASK 21
#define SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG 0x0002
#define SDL_GL_CONTEXT_PROFILE_ES 0x0004

#define EGL_NONE 0x3038
#define EGL_RENDERABLE_TYPE 0x3040
#define EGL_OPENGL_ES2_BIT 0x0004
#define EGL_OPENGL_BIT 0x0008
#define EGL_OPENGL_ES3_BIT_KHR 0x0040
#define EGL_CONTEXT_CLIENT_VERSION 0x3098
#define EGL_GL_COLORSPACE_KHR 0x309D
#define EGL_CONTEXT_MINOR_VERSION_KHR 0x30FB
#define EGL_CONTEXT_FLAGS_KHR 0x30FC
#define EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR 0x30FD

typedef struct FakeWindow {
    char title[64];
    int w;
    int h;
    uint64_t flags;
    bool shown;
} FakeWindow;

static FakeWindow* sFakeAndroidWindow = NULL;
static int sDestroyCount = 0;
static int sGlAttrs[32];

static bool fake_SDL_GL_SetAttribute(int attr, int value) {
    if (attr >= 0 && attr < 32) {
        sGlAttrs[attr] = value;
    }
    return true;
}

static uint64_t fake_SDL_GetWindowFlags(void* window) {
    return window != NULL ? ((FakeWindow*) window)->flags : 0ULL;
}

static bool fake_SDL_SetWindowTitle(void* window, const char* title) {
    if (window == NULL || title == NULL) return false;
    snprintf(((FakeWindow*) window)->title, sizeof(((FakeWindow*) window)->title), "%s", title);
    return true;
}

static bool fake_SDL_ShowWindow(void* window) {
    if (window == NULL) return false;
    ((FakeWindow*) window)->flags &= ~SDL_WINDOW_HIDDEN;
    ((FakeWindow*) window)->shown = true;
    return true;
}

static bool fake_SDL_HideWindow(void* window) {
    if (window == NULL) return false;
    ((FakeWindow*) window)->flags |= SDL_WINDOW_HIDDEN;
    ((FakeWindow*) window)->shown = false;
    return true;
}

static bool fake_SDL_SetWindowSize(void* window, int w, int h) {
    (void) window;
    (void) w;
    (void) h;
    // Upstream Android SDL3 has no SetWindowSize implementation and returns false.
    return false;
}

static void* fake_SDL_CreateWindow(const char* title, int w, int h, uint64_t flags) {
    // Upstream Android SDL3 rejects creating a second window when Android_Window != NULL.
    if (sFakeAndroidWindow != NULL) {
        return NULL;
    }
    FakeWindow* win = (FakeWindow*) calloc(1, sizeof(FakeWindow));
    snprintf(win->title, sizeof(win->title), "%s", title != NULL ? title : "");
    win->w = w;
    win->h = h;
    win->flags = flags;
    win->shown = (flags & SDL_WINDOW_HIDDEN) == 0;
    sFakeAndroidWindow = win;
    return win;
}

static void fake_SDL_DestroyWindow(void* window) {
    if (window == NULL) return;
    if (window == sFakeAndroidWindow) {
        sFakeAndroidWindow = NULL;
    }
    sDestroyCount++;
    free(window);
}

static bool fake_SDL_GL_MakeCurrent(void* window, void* context) {
    // Simulate a platform/renderer that rejects surfaceless MakeCurrent(NULL, ctx).
    if (window == NULL && context != NULL) {
        return false;
    }
    return true;
}

static int sChooseConfigCalls = 0;
static int fake_eglChooseConfig(void* dpy, const int* attrib_list, void** configs, int config_size, int* num_config) {
    (void) dpy;
    (void) configs;
    (void) config_size;
    sChooseConfigCalls++;
    int renderable = 0;
    if (attrib_list != NULL) {
        for (int i = 0; attrib_list[i] != EGL_NONE; i += 2) {
            if (attrib_list[i] == EGL_RENDERABLE_TYPE) {
                renderable = attrib_list[i + 1];
            }
        }
    }
    // Simulate a strict driver that rejects EGL_OPENGL_BIT and EGL_OPENGL_ES3_BIT_KHR in eglChooseConfig,
    // succeeding only when normalized to EGL_OPENGL_ES2_BIT.
    if (renderable == EGL_OPENGL_ES2_BIT) {
        if (num_config != NULL) *num_config = 1;
        return 1;
    }
    if (num_config != NULL) *num_config = 0;
    return 1;
}

static int sLastContextClientVersion = 0;
static void* fake_eglCreateContext(void* dpy, void* config, void* share_context, const int* attrib_list) {
    (void) dpy;
    (void) config;
    (void) share_context;
    if (attrib_list != NULL) {
        for (int i = 0; attrib_list[i] != EGL_NONE; i += 2) {
            // Reject desktop GL / KHR minor/flags/profile attributes that fail on GLES-only drivers.
            if (attrib_list[i] == EGL_CONTEXT_MINOR_VERSION_KHR ||
                attrib_list[i] == EGL_CONTEXT_FLAGS_KHR ||
                attrib_list[i] == EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR) {
                return NULL;
            }
            if (attrib_list[i] == EGL_CONTEXT_CLIENT_VERSION) {
                sLastContextClientVersion = attrib_list[i + 1];
            }
        }
    }
    return sLastContextClientVersion == 3 ? (void*) 0x12345678 : NULL;
}

static void* fake_eglCreateWindowSurface(void* dpy, void* config, void* win, const int* attrib_list) {
    (void) dpy;
    (void) config;
    (void) win;
    if (attrib_list != NULL) {
        for (int i = 0; attrib_list[i] != EGL_NONE; i += 2) {
            if (attrib_list[i] == EGL_GL_COLORSPACE_KHR) {
                return NULL;
            }
        }
    }
    return (void*) 0x87654321;
}

typedef void (*SDL_FunctionPointer)(void);
static SDL_FunctionPointer fake_SDL_EGL_GetProcAddress(const char* proc) {
    if (strcmp(proc, "eglChooseConfig") == 0) return (SDL_FunctionPointer) &fake_eglChooseConfig;
    if (strcmp(proc, "eglCreateContext") == 0) return (SDL_FunctionPointer) &fake_eglCreateContext;
    if (strcmp(proc, "eglCreateWindowSurface") == 0) return (SDL_FunctionPointer) &fake_eglCreateWindowSurface;
    return NULL;
}

void* test_dlopen(const char* filename, int flag) {
    (void) filename;
    (void) flag;
    return (void*) 0x1;
}

void* test_dlsym(void* handle, const char* symbol) {
    (void) handle;
    if (strcmp(symbol, "SDL_GL_SetAttribute") == 0) return (void*) &fake_SDL_GL_SetAttribute;
    if (strcmp(symbol, "SDL_GetWindowFlags") == 0) return (void*) &fake_SDL_GetWindowFlags;
    if (strcmp(symbol, "SDL_SetWindowTitle") == 0) return (void*) &fake_SDL_SetWindowTitle;
    if (strcmp(symbol, "SDL_ShowWindow") == 0) return (void*) &fake_SDL_ShowWindow;
    return NULL;
}

typedef void* (*CreateWindowFn)(const char*, int, int, uint64_t);
typedef void (*DestroyWindowFn)(void*);
typedef bool (*SetWindowSizeFn)(void*, int, int);
typedef bool (*HideWindowFn)(void*);
typedef bool (*GlSetAttrFn)(int, int);
typedef bool (*GlMakeCurrentFn)(void*, void*);
typedef SDL_FunctionPointer (*EglGetProcFn)(const char*);
typedef int (*EglChooseConfigFn)(void*, const int*, void**, int, int*);
typedef void* (*EglCreateContextFn)(void*, void*, void*, const int*);
typedef void* (*EglCreateWindowSurfaceFn)(void*, void*, void*, const int*);

int main(void) {
    CreateWindowFn createWin = (CreateWindowFn) sdlDlsymProxy(NULL, "SDL_CreateWindow", (void*) &fake_SDL_CreateWindow);
    DestroyWindowFn destroyWin = (DestroyWindowFn) sdlDlsymProxy(NULL, "SDL_DestroyWindow", (void*) &fake_SDL_DestroyWindow);
    SetWindowSizeFn setWinSize = (SetWindowSizeFn) sdlDlsymProxy(NULL, "SDL_SetWindowSize", (void*) &fake_SDL_SetWindowSize);
    HideWindowFn hideWin = (HideWindowFn) sdlDlsymProxy(NULL, "SDL_HideWindow", (void*) &fake_SDL_HideWindow);
    GlSetAttrFn setAttr = (GlSetAttrFn) sdlDlsymProxy(NULL, "SDL_GL_SetAttribute", (void*) &fake_SDL_GL_SetAttribute);
    GlMakeCurrentFn makeCurrent = (GlMakeCurrentFn) sdlDlsymProxy(NULL, "SDL_GL_MakeCurrent", (void*) &fake_SDL_GL_MakeCurrent);
    EglGetProcFn getEglProc = (EglGetProcFn) sdlDlsymProxy(NULL, "SDL_EGL_GetProcAddress", (void*) &fake_SDL_EGL_GetProcAddress);

    // 1. Test Vulkan probe failure -> OpenGL fallback on single-window Android SDL3
    setenv("POJAV_GRAPHICS_BACKEND", "default", 1);
    void* vkWin = createWin("Vulkan Probe", 1280, 720, SDL_WINDOW_VULKAN | SDL_WINDOW_HIDDEN);
    assert(vkWin != NULL);
    assert((((FakeWindow*) vkWin)->flags & SDL_WINDOW_VULKAN) != 0);
    assert((((FakeWindow*) vkWin)->flags & SDL_WINDOW_OPENGL) == 0);

    // Now Minecraft falls back to OpenGL without having destroyed vkWin yet:
    void* glUtilityWin = createWin("RenderPearl Utility", 64, 64, SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN);
    assert(glUtilityWin != NULL);
    assert(sDestroyCount == 1); // vkWin was destroyed so Android_Window could be recreated with OpenGL!
    assert((((FakeWindow*) glUtilityWin)->flags & SDL_WINDOW_OPENGL) != 0);

    // Late destroy of replaced vkWin should be safely ignored
    destroyWin(vkWin);
    assert(sFakeAndroidWindow == glUtilityWin);

    // 2. Test second OpenGL window (Minecraft main window) reuses primary window on single-window Android
    void* glMainWin = createWin("Minecraft 26.3", 1920, 1080, SDL_WINDOW_OPENGL);
    assert(glMainWin == glUtilityWin);
    assert(strcmp(((FakeWindow*) glMainWin)->title, "Minecraft 26.3") == 0);
    assert(((FakeWindow*) glMainWin)->shown == true);

    // Hiding the utility window while shared with main window must not hide the primary window
    assert(hideWin(glUtilityWin) == true);
    assert(((FakeWindow*) glMainWin)->shown == true);

    // Destroying the utility window must keep the shared OpenGL primary window alive
    destroyWin(glUtilityWin);
    assert(sFakeAndroidWindow == glMainWin);
    assert(sDestroyCount == 1);

    // SDL_SetWindowSize must succeed (return true) even though Android SDL3 lacks SetWindowSize
    assert(setWinSize(glMainWin, 1920, 1080) == true);

    // Surfaceless SDL_GL_MakeCurrent(NULL, ctx) falls back to sPrimaryWindow
    assert(makeCurrent(NULL, (void*) 0xCAFE) == true);

    // 3. Test SDL_GL_SetAttribute normalization for GLES renderers
    setAttr(SDL_GL_CONTEXT_PROFILE_MASK, 1 /* CORE */);
    setAttr(SDL_GL_CONTEXT_FLAGS, SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG);
    setAttr(SDL_GL_CONTEXT_MAJOR_VERSION, 4);
    setAttr(SDL_GL_CONTEXT_MINOR_VERSION, 3);
    assert(sGlAttrs[SDL_GL_CONTEXT_PROFILE_MASK] == SDL_GL_CONTEXT_PROFILE_ES);
    assert((sGlAttrs[SDL_GL_CONTEXT_FLAGS] & SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG) == 0);
    assert(sGlAttrs[SDL_GL_CONTEXT_MAJOR_VERSION] == 3);
    assert(sGlAttrs[SDL_GL_CONTEXT_MINOR_VERSION] == 0);

    // 4. Test EGL config/context/surface wrappers
    EglChooseConfigFn chooseCfg = (EglChooseConfigFn) getEglProc("eglChooseConfig");
    int cfgAttribs[] = { EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_NONE };
    int numCfg = 0;
    assert(chooseCfg(NULL, cfgAttribs, NULL, 0, &numCfg) == 1);
    assert(numCfg == 1);

    EglCreateContextFn createCtx = (EglCreateContextFn) getEglProc("eglCreateContext");
    int ctxAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_CONTEXT_MINOR_VERSION_KHR, 3,
        EGL_CONTEXT_FLAGS_KHR, 2,
        EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR, 1,
        EGL_NONE
    };
    assert(createCtx(NULL, NULL, NULL, ctxAttribs) == (void*) 0x12345678);
    assert(sLastContextClientVersion == 3);

    EglCreateWindowSurfaceFn createSurf = (EglCreateWindowSurfaceFn) getEglProc("eglCreateWindowSurface");
    int surfAttribs[] = { EGL_GL_COLORSPACE_KHR, 0x3089, EGL_NONE };
    assert(createSurf(NULL, NULL, NULL, surfAttribs) == (void*) 0x87654321);

    return 0;
}

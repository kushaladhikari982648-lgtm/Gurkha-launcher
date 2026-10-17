/*
 * EGL front for running the LTW renderer under SDL3 (Minecraft 26.3 and newer).
 *
 * SDL creates the game's GL context itself, through the library named by SDL_EGL_LIBRARY. Two things stop
 * it from using libltw.so directly:
 *  - SDL loads every core EGL function with dlsym(), but libltw.so only defines eglCreateContext,
 *    eglDestroyContext, eglMakeCurrent and eglGetProcAddress (and aborts on contexts it did not create);
 *  - Minecraft asks for a desktop OpenGL core context, so SDL binds EGL_OPENGL_API and chooses
 *    EGL_OPENGL_BIT configs, which Android's EGL rejects.
 * This library exports the whole EGL 1.5 core API: the four functions above go to LTW, everything else to
 * the system EGL, and desktop OpenGL requests are turned into OpenGL ES 3 requests (on which LTW provides
 * desktop OpenGL), as MojoLauncher's SDL fork does when it forces a GLES context for LTW.
 *
 * Copyright (C) 2026 Gurkha Launcher contributors. Licensed under the GPL-3.0, like the launcher.
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

#define TAG "LtwSdlEgl"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define EXPORT __attribute__((visibility("default"), used))

#ifndef EGL_OPENGL_ES3_BIT_KHR
#define EGL_OPENGL_ES3_BIT_KHR 0x00000040
#endif
#ifndef EGL_CONTEXT_MINOR_VERSION_KHR
#define EGL_CONTEXT_MINOR_VERSION_KHR 0x30FB
#endif
#ifndef EGL_CONTEXT_FLAGS_KHR
#define EGL_CONTEXT_FLAGS_KHR 0x30FC
#endif
#ifndef EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR
#define EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR 0x30FD
#endif
#ifndef EGL_CONTEXT_OPENGL_NO_ERROR_KHR
#define EGL_CONTEXT_OPENGL_NO_ERROR_KHR 0x31B3
#endif
#ifndef EGL_GL_COLORSPACE_KHR
#define EGL_GL_COLORSPACE_KHR 0x309D
#endif
#define MAX_ATTRIBS 64

typedef __eglMustCastToProperFunctionPointerType proc_t;

static pthread_once_t init_once = PTHREAD_ONCE_INIT;
static void *host_egl = NULL;
static void *ltw = NULL;
// The API the caller bound with eglBindAPI. EGL keeps the bound API per thread.
static __thread EGLenum requested_api = EGL_OPENGL_ES_API;

#define HOST_FUNC(ret, name, args) static ret (*host_##name) args = NULL;
HOST_FUNC(EGLBoolean, eglChooseConfig, (EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *))
HOST_FUNC(EGLBoolean, eglCopyBuffers, (EGLDisplay, EGLSurface, EGLNativePixmapType))
HOST_FUNC(EGLSurface, eglCreatePbufferSurface, (EGLDisplay, EGLConfig, const EGLint *))
HOST_FUNC(EGLSurface, eglCreatePixmapSurface, (EGLDisplay, EGLConfig, EGLNativePixmapType, const EGLint *))
HOST_FUNC(EGLSurface, eglCreateWindowSurface, (EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *))
HOST_FUNC(EGLBoolean, eglDestroySurface, (EGLDisplay, EGLSurface))
HOST_FUNC(EGLBoolean, eglGetConfigAttrib, (EGLDisplay, EGLConfig, EGLint, EGLint *))
HOST_FUNC(EGLBoolean, eglGetConfigs, (EGLDisplay, EGLConfig *, EGLint, EGLint *))
HOST_FUNC(EGLDisplay, eglGetCurrentDisplay, (void))
HOST_FUNC(EGLSurface, eglGetCurrentSurface, (EGLint))
HOST_FUNC(EGLDisplay, eglGetDisplay, (EGLNativeDisplayType))
HOST_FUNC(EGLint, eglGetError, (void))
HOST_FUNC(EGLBoolean, eglInitialize, (EGLDisplay, EGLint *, EGLint *))
HOST_FUNC(EGLBoolean, eglQueryContext, (EGLDisplay, EGLContext, EGLint, EGLint *))
HOST_FUNC(const char *, eglQueryString, (EGLDisplay, EGLint))
HOST_FUNC(EGLBoolean, eglQuerySurface, (EGLDisplay, EGLSurface, EGLint, EGLint *))
HOST_FUNC(EGLBoolean, eglSwapBuffers, (EGLDisplay, EGLSurface))
HOST_FUNC(EGLBoolean, eglTerminate, (EGLDisplay))
HOST_FUNC(EGLBoolean, eglWaitGL, (void))
HOST_FUNC(EGLBoolean, eglWaitNative, (EGLint))
HOST_FUNC(EGLBoolean, eglBindTexImage, (EGLDisplay, EGLSurface, EGLint))
HOST_FUNC(EGLBoolean, eglReleaseTexImage, (EGLDisplay, EGLSurface, EGLint))
HOST_FUNC(EGLBoolean, eglSurfaceAttrib, (EGLDisplay, EGLSurface, EGLint, EGLint))
HOST_FUNC(EGLBoolean, eglSwapInterval, (EGLDisplay, EGLint))
HOST_FUNC(EGLBoolean, eglBindAPI, (EGLenum))
HOST_FUNC(EGLenum, eglQueryAPI, (void))
HOST_FUNC(EGLSurface, eglCreatePbufferFromClientBuffer, (EGLDisplay, EGLenum, EGLClientBuffer, EGLConfig, const EGLint *))
HOST_FUNC(EGLBoolean, eglReleaseThread, (void))
HOST_FUNC(EGLBoolean, eglWaitClient, (void))
HOST_FUNC(EGLContext, eglGetCurrentContext, (void))
HOST_FUNC(EGLSync, eglCreateSync, (EGLDisplay, EGLenum, const EGLAttrib *))
HOST_FUNC(EGLBoolean, eglDestroySync, (EGLDisplay, EGLSync))
HOST_FUNC(EGLint, eglClientWaitSync, (EGLDisplay, EGLSync, EGLint, EGLTime))
HOST_FUNC(EGLBoolean, eglGetSyncAttrib, (EGLDisplay, EGLSync, EGLint, EGLAttrib *))
HOST_FUNC(EGLImage, eglCreateImage, (EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLAttrib *))
HOST_FUNC(EGLBoolean, eglDestroyImage, (EGLDisplay, EGLImage))
HOST_FUNC(EGLDisplay, eglGetPlatformDisplay, (EGLenum, void *, const EGLAttrib *))
HOST_FUNC(EGLSurface, eglCreatePlatformWindowSurface, (EGLDisplay, EGLConfig, void *, const EGLAttrib *))
HOST_FUNC(EGLSurface, eglCreatePlatformPixmapSurface, (EGLDisplay, EGLConfig, void *, const EGLAttrib *))
HOST_FUNC(EGLBoolean, eglWaitSync, (EGLDisplay, EGLSync, EGLint))
#undef HOST_FUNC

static EGLContext (*ltw_eglCreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint *) = NULL;
static EGLBoolean (*ltw_eglDestroyContext)(EGLDisplay, EGLContext) = NULL;
static EGLBoolean (*ltw_eglMakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext) = NULL;
static proc_t (*ltw_eglGetProcAddress)(const char *) = NULL;

static void load_libraries(void) {
    host_egl = dlopen("libEGL.so", RTLD_NOW | RTLD_LOCAL);
    if (host_egl == NULL) {
        LOGE("Failed to load the system EGL: %s", dlerror());
        abort();
    }
#define LOAD_HOST(name) host_##name = dlsym(host_egl, #name);
    LOAD_HOST(eglChooseConfig) LOAD_HOST(eglCopyBuffers) LOAD_HOST(eglCreatePbufferSurface)
    LOAD_HOST(eglCreatePixmapSurface) LOAD_HOST(eglCreateWindowSurface) LOAD_HOST(eglDestroySurface)
    LOAD_HOST(eglGetConfigAttrib) LOAD_HOST(eglGetConfigs) LOAD_HOST(eglGetCurrentDisplay)
    LOAD_HOST(eglGetCurrentSurface) LOAD_HOST(eglGetDisplay) LOAD_HOST(eglGetError)
    LOAD_HOST(eglInitialize) LOAD_HOST(eglQueryContext) LOAD_HOST(eglQueryString)
    LOAD_HOST(eglQuerySurface) LOAD_HOST(eglSwapBuffers) LOAD_HOST(eglTerminate)
    LOAD_HOST(eglWaitGL) LOAD_HOST(eglWaitNative) LOAD_HOST(eglBindTexImage)
    LOAD_HOST(eglReleaseTexImage) LOAD_HOST(eglSurfaceAttrib) LOAD_HOST(eglSwapInterval)
    LOAD_HOST(eglBindAPI) LOAD_HOST(eglQueryAPI) LOAD_HOST(eglCreatePbufferFromClientBuffer)
    LOAD_HOST(eglReleaseThread) LOAD_HOST(eglWaitClient) LOAD_HOST(eglGetCurrentContext)
    LOAD_HOST(eglCreateSync) LOAD_HOST(eglDestroySync) LOAD_HOST(eglClientWaitSync)
    LOAD_HOST(eglGetSyncAttrib) LOAD_HOST(eglCreateImage) LOAD_HOST(eglDestroyImage)
    LOAD_HOST(eglGetPlatformDisplay) LOAD_HOST(eglCreatePlatformWindowSurface)
    LOAD_HOST(eglCreatePlatformPixmapSurface) LOAD_HOST(eglWaitSync)
#undef LOAD_HOST

    const char *ltw_path = getenv("LTW_SDL_EGL_LTW_LIBRARY");
    ltw = dlopen(ltw_path != NULL ? ltw_path : "libltw.so", RTLD_NOW | RTLD_LOCAL);
    if (ltw == NULL) {
        LOGE("Failed to load LTW: %s", dlerror());
        abort();
    }
    ltw_eglCreateContext = dlsym(ltw, "eglCreateContext");
    ltw_eglDestroyContext = dlsym(ltw, "eglDestroyContext");
    ltw_eglMakeCurrent = dlsym(ltw, "eglMakeCurrent");
    ltw_eglGetProcAddress = dlsym(ltw, "eglGetProcAddress");
    if (!ltw_eglCreateContext || !ltw_eglDestroyContext || !ltw_eglMakeCurrent || !ltw_eglGetProcAddress) {
        LOGE("libltw.so is missing EGL entry points");
        abort();
    }
    LOGI("LTW EGL front ready");
}

static inline void init(void) {
    pthread_once(&init_once, load_libraries);
}

static bool emulating_desktop_gl(void) {
    return requested_api == EGL_OPENGL_API;
}

// Copies an EGL attribute list without the pairs whose key is in `drop`.
static void filter_attribs(const EGLint *in, EGLint *out, const EGLint *drop, int ndrop) {
    int n = 0;
    if (in != NULL) {
        for (; in[0] != EGL_NONE && n < MAX_ATTRIBS - 2; in += 2) {
            bool skip = false;
            for (int i = 0; i < ndrop; i++) if (in[0] == drop[i]) skip = true;
            if (skip) continue;
            out[n++] = in[0];
            out[n++] = in[1];
        }
    }
    out[n] = EGL_NONE;
}

static void filter_attribs64(const EGLAttrib *in, EGLAttrib *out, EGLAttrib drop) {
    int n = 0;
    if (in != NULL) {
        for (; in[0] != EGL_NONE && n < MAX_ATTRIBS - 2; in += 2) {
            if (in[0] == drop) continue;
            out[n++] = in[0];
            out[n++] = in[1];
        }
    }
    out[n] = EGL_NONE;
}

EXPORT EGLBoolean eglBindAPI(EGLenum api) {
    init();
    requested_api = api;
    // Desktop OpenGL is provided by LTW on top of OpenGL ES
    return host_eglBindAPI(api == EGL_OPENGL_API ? EGL_OPENGL_ES_API : api);
}

EXPORT EGLenum eglQueryAPI(void) {
    init();
    return emulating_desktop_gl() ? EGL_OPENGL_API : host_eglQueryAPI();
}

EXPORT EGLBoolean eglChooseConfig(EGLDisplay dpy, const EGLint *attrib_list, EGLConfig *configs, EGLint config_size, EGLint *num_config) {
    init();
    EGLint attribs[MAX_ATTRIBS];
    int n = 0, renderable_index = -1;
    if (attrib_list != NULL) {
        for (const EGLint *a = attrib_list; a[0] != EGL_NONE && n < MAX_ATTRIBS - 2; a += 2) {
            attribs[n] = a[0];
            attribs[n + 1] = a[1];
            if (a[0] == EGL_RENDERABLE_TYPE && (a[1] & EGL_OPENGL_BIT)) {
                attribs[n + 1] = (a[1] & ~(EGL_OPENGL_BIT | EGL_OPENGL_ES_BIT)) | EGL_OPENGL_ES3_BIT_KHR;
                renderable_index = n + 1;
            }
            n += 2;
        }
    }
    attribs[n] = EGL_NONE;
    EGLBoolean result = host_eglChooseConfig(dpy, attribs, configs, config_size, num_config);
    if (renderable_index >= 0 && (!result || (num_config != NULL && *num_config == 0))) {
        // No OpenGL ES 3 config (or no EGL_KHR_create_context): LTW still needs at least OpenGL ES 3, try ES 2 configs
        attribs[renderable_index] = (attribs[renderable_index] & ~EGL_OPENGL_ES3_BIT_KHR) | EGL_OPENGL_ES2_BIT;
        result = host_eglChooseConfig(dpy, attribs, configs, config_size, num_config);
    }
    return result;
}

EXPORT EGLContext eglCreateContext(EGLDisplay dpy, EGLConfig config, EGLContext share_context, const EGLint *attrib_list) {
    init();
    bool desktop = emulating_desktop_gl();
    if (attrib_list != NULL) {
        for (const EGLint *a = attrib_list; a[0] != EGL_NONE; a += 2) {
            if (a[0] == EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR) desktop = true;
        }
    }
    if (!desktop) return ltw_eglCreateContext(dpy, config, share_context, attrib_list);

    // OpenGL ES 3.0 (drivers return the newest compatible version); LTW exposes desktop OpenGL on it
    EGLint attribs[MAX_ATTRIBS + 4];
    const EGLint drop[] = {
            EGL_CONTEXT_CLIENT_VERSION, // same value as EGL_CONTEXT_MAJOR_VERSION_KHR
            EGL_CONTEXT_MINOR_VERSION_KHR,
            EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR,
            EGL_CONTEXT_FLAGS_KHR,
            EGL_CONTEXT_OPENGL_NO_ERROR_KHR,
    };
    filter_attribs(attrib_list, attribs + 4, drop, sizeof(drop) / sizeof(drop[0]));
    attribs[0] = EGL_CONTEXT_CLIENT_VERSION;
    attribs[1] = 3;
    attribs[2] = EGL_CONTEXT_MINOR_VERSION_KHR;
    attribs[3] = 0;
    host_eglBindAPI(EGL_OPENGL_ES_API);
    EGLContext context = ltw_eglCreateContext(dpy, config, share_context, attribs);
    if (context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext (OpenGL ES 3 for LTW) failed: 0x%x", host_eglGetError());
    }
    return context;
}

EXPORT EGLBoolean eglDestroyContext(EGLDisplay dpy, EGLContext ctx) {
    init();
    return ltw_eglDestroyContext(dpy, ctx);
}

EXPORT EGLBoolean eglMakeCurrent(EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx) {
    init();
    return ltw_eglMakeCurrent(dpy, draw, read, ctx);
}

// sRGB window surfaces are not needed with LTW and are refused by some drivers for these contexts
static const EGLint colorspace_attrib[] = { EGL_GL_COLORSPACE_KHR };

EXPORT EGLSurface eglCreateWindowSurface(EGLDisplay dpy, EGLConfig config, EGLNativeWindowType win, const EGLint *attrib_list) {
    init();
    if (!emulating_desktop_gl()) return host_eglCreateWindowSurface(dpy, config, win, attrib_list);
    EGLint attribs[MAX_ATTRIBS];
    filter_attribs(attrib_list, attribs, colorspace_attrib, 1);
    return host_eglCreateWindowSurface(dpy, config, win, attribs);
}

EXPORT EGLSurface eglCreatePbufferSurface(EGLDisplay dpy, EGLConfig config, const EGLint *attrib_list) {
    init();
    if (!emulating_desktop_gl()) return host_eglCreatePbufferSurface(dpy, config, attrib_list);
    EGLint attribs[MAX_ATTRIBS];
    filter_attribs(attrib_list, attribs, colorspace_attrib, 1);
    return host_eglCreatePbufferSurface(dpy, config, attribs);
}

EXPORT EGLSurface eglCreatePlatformWindowSurface(EGLDisplay dpy, EGLConfig config, void *native_window, const EGLAttrib *attrib_list) {
    init();
    if (host_eglCreatePlatformWindowSurface == NULL) return EGL_NO_SURFACE;
    if (!emulating_desktop_gl()) return host_eglCreatePlatformWindowSurface(dpy, config, native_window, attrib_list);
    EGLAttrib attribs[MAX_ATTRIBS];
    filter_attribs64(attrib_list, attribs, EGL_GL_COLORSPACE_KHR);
    return host_eglCreatePlatformWindowSurface(dpy, config, native_window, attribs);
}

// Everything else goes to the system EGL unchanged.
#define FORWARD(ret, name, params, args)            \
    EXPORT ret name params {                        \
        init();                                     \
        if (host_##name == NULL) return (ret) 0;    \
        return host_##name args;                    \
    }
FORWARD(EGLBoolean, eglCopyBuffers, (EGLDisplay dpy, EGLSurface surface, EGLNativePixmapType target), (dpy, surface, target))
FORWARD(EGLSurface, eglCreatePixmapSurface, (EGLDisplay dpy, EGLConfig config, EGLNativePixmapType pixmap, const EGLint *attrib_list), (dpy, config, pixmap, attrib_list))
FORWARD(EGLBoolean, eglDestroySurface, (EGLDisplay dpy, EGLSurface surface), (dpy, surface))
FORWARD(EGLBoolean, eglGetConfigAttrib, (EGLDisplay dpy, EGLConfig config, EGLint attribute, EGLint *value), (dpy, config, attribute, value))
FORWARD(EGLBoolean, eglGetConfigs, (EGLDisplay dpy, EGLConfig *configs, EGLint config_size, EGLint *num_config), (dpy, configs, config_size, num_config))
FORWARD(EGLDisplay, eglGetCurrentDisplay, (void), ())
FORWARD(EGLSurface, eglGetCurrentSurface, (EGLint readdraw), (readdraw))
FORWARD(EGLDisplay, eglGetDisplay, (EGLNativeDisplayType display_id), (display_id))
FORWARD(EGLint, eglGetError, (void), ())
FORWARD(EGLBoolean, eglInitialize, (EGLDisplay dpy, EGLint *major, EGLint *minor), (dpy, major, minor))
FORWARD(EGLBoolean, eglQueryContext, (EGLDisplay dpy, EGLContext ctx, EGLint attribute, EGLint *value), (dpy, ctx, attribute, value))
FORWARD(const char *, eglQueryString, (EGLDisplay dpy, EGLint name), (dpy, name))
FORWARD(EGLBoolean, eglQuerySurface, (EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint *value), (dpy, surface, attribute, value))
FORWARD(EGLBoolean, eglSwapBuffers, (EGLDisplay dpy, EGLSurface surface), (dpy, surface))
FORWARD(EGLBoolean, eglTerminate, (EGLDisplay dpy), (dpy))
FORWARD(EGLBoolean, eglWaitGL, (void), ())
FORWARD(EGLBoolean, eglWaitNative, (EGLint engine), (engine))
FORWARD(EGLBoolean, eglBindTexImage, (EGLDisplay dpy, EGLSurface surface, EGLint buffer), (dpy, surface, buffer))
FORWARD(EGLBoolean, eglReleaseTexImage, (EGLDisplay dpy, EGLSurface surface, EGLint buffer), (dpy, surface, buffer))
FORWARD(EGLBoolean, eglSurfaceAttrib, (EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint value), (dpy, surface, attribute, value))
FORWARD(EGLBoolean, eglSwapInterval, (EGLDisplay dpy, EGLint interval), (dpy, interval))
FORWARD(EGLSurface, eglCreatePbufferFromClientBuffer, (EGLDisplay dpy, EGLenum buftype, EGLClientBuffer buffer, EGLConfig config, const EGLint *attrib_list), (dpy, buftype, buffer, config, attrib_list))
FORWARD(EGLBoolean, eglReleaseThread, (void), ())
FORWARD(EGLBoolean, eglWaitClient, (void), ())
FORWARD(EGLContext, eglGetCurrentContext, (void), ())
FORWARD(EGLSync, eglCreateSync, (EGLDisplay dpy, EGLenum type, const EGLAttrib *attrib_list), (dpy, type, attrib_list))
FORWARD(EGLBoolean, eglDestroySync, (EGLDisplay dpy, EGLSync sync), (dpy, sync))
FORWARD(EGLint, eglClientWaitSync, (EGLDisplay dpy, EGLSync sync, EGLint flags, EGLTime timeout), (dpy, sync, flags, timeout))
FORWARD(EGLBoolean, eglGetSyncAttrib, (EGLDisplay dpy, EGLSync sync, EGLint attribute, EGLAttrib *value), (dpy, sync, attribute, value))
FORWARD(EGLImage, eglCreateImage, (EGLDisplay dpy, EGLContext ctx, EGLenum target, EGLClientBuffer buffer, const EGLAttrib *attrib_list), (dpy, ctx, target, buffer, attrib_list))
FORWARD(EGLBoolean, eglDestroyImage, (EGLDisplay dpy, EGLImage image), (dpy, image))
FORWARD(EGLDisplay, eglGetPlatformDisplay, (EGLenum platform, void *native_display, const EGLAttrib *attrib_list), (platform, native_display, attrib_list))
FORWARD(EGLSurface, eglCreatePlatformPixmapSurface, (EGLDisplay dpy, EGLConfig config, void *native_pixmap, const EGLAttrib *attrib_list), (dpy, config, native_pixmap, attrib_list))
FORWARD(EGLBoolean, eglWaitSync, (EGLDisplay dpy, EGLSync sync, EGLint flags), (dpy, sync, flags))
#undef FORWARD

static const struct {
    const char *name;
    proc_t function;
} own_functions[] = {
        { "eglBindAPI", (proc_t) eglBindAPI },
        { "eglQueryAPI", (proc_t) eglQueryAPI },
        { "eglChooseConfig", (proc_t) eglChooseConfig },
        { "eglCreateContext", (proc_t) eglCreateContext },
        { "eglDestroyContext", (proc_t) eglDestroyContext },
        { "eglMakeCurrent", (proc_t) eglMakeCurrent },
        { "eglCreateWindowSurface", (proc_t) eglCreateWindowSurface },
        { "eglCreatePbufferSurface", (proc_t) eglCreatePbufferSurface },
        { "eglCreatePlatformWindowSurface", (proc_t) eglCreatePlatformWindowSurface },
};

EXPORT proc_t eglGetProcAddress(const char *procname) {
    init();
    if (procname == NULL) return NULL;
    for (size_t i = 0; i < sizeof(own_functions) / sizeof(own_functions[0]); i++) {
        if (strcmp(procname, own_functions[i].name) == 0) return own_functions[i].function;
    }
    // OpenGL functions come from LTW; other EGL functions and extensions from the system (through LTW)
    return ltw_eglGetProcAddress(procname);
}

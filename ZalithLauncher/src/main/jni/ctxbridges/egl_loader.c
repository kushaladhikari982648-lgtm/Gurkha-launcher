//
// Created by maks on 21.09.2022.
//
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <string.h>
#include "br_loader.h"
#include "egl_loader.h"

EGLBoolean (*eglMakeCurrent_p) (EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx);
EGLBoolean (*eglDestroyContext_p) (EGLDisplay dpy, EGLContext ctx);
EGLBoolean (*eglDestroySurface_p) (EGLDisplay dpy, EGLSurface surface);
EGLBoolean (*eglTerminate_p) (EGLDisplay dpy);
EGLBoolean (*eglReleaseThread_p) (void);
EGLContext (*eglGetCurrentContext_p) (void);
EGLDisplay (*eglGetDisplay_p) (NativeDisplayType display);
EGLBoolean (*eglInitialize_p) (EGLDisplay dpy, EGLint *major, EGLint *minor);
EGLBoolean (*eglChooseConfig_p) (EGLDisplay dpy, const EGLint *attrib_list, EGLConfig *configs, EGLint config_size, EGLint *num_config);
EGLBoolean (*eglGetConfigAttrib_p) (EGLDisplay dpy, EGLConfig config, EGLint attribute, EGLint *value);
EGLBoolean (*eglBindAPI_p) (EGLenum api);
EGLSurface (*eglCreatePbufferSurface_p) (EGLDisplay dpy, EGLConfig config, const EGLint *attrib_list);
EGLSurface (*eglCreateWindowSurface_p) (EGLDisplay dpy, EGLConfig config, NativeWindowType window, const EGLint *attrib_list);
EGLBoolean (*eglSwapBuffers_p) (EGLDisplay dpy, EGLSurface draw);
EGLint (*eglGetError_p) (void);
EGLContext (*eglCreateContext_p) (EGLDisplay dpy, EGLConfig config, EGLContext share_list, const EGLint *attrib_list);
EGLBoolean (*eglSwapInterval_p) (EGLDisplay dpy, EGLint interval);
EGLSurface (*eglGetCurrentSurface_p) (EGLint readdraw);
EGLBoolean (*eglQuerySurface_p)(EGLDisplay display, EGLSurface surface, EGLint attribute, EGLint * value);

/* Contexts and dispatch pointers stay with one provider for the game process. */
static void *provider_handle;

bool dlsym_EGL(void) {
    if (provider_handle) return true;
    const char *gles = getenv("LIBGL_GLES");
    const char *provider = getenv("POJAVEXEC_EGL");
    if (gles && !strncmp(gles, "libGLESv2_angle.so", 18)) provider = "libEGL_angle.so";
    if (!provider || !*provider) provider = "libEGL.so";
    void *handle = dlopen(provider, RTLD_LOCAL | RTLD_NOW);
    if (!handle) {
        fprintf(stderr, "EGLBridge: provider '%s' failed: %s (no fallback)\n", provider, dlerror());
        return false;
    }
    /* Resolve all required functions before publishing ANY dispatch pointer. */
#define RESOLVE(name) __typeof__(name##_p) candidate_##name = GLGetProcAddress(handle, #name); \
    if (!candidate_##name) { fprintf(stderr, "EGLBridge: %s missing %s\n", provider, #name); goto failed; }
    RESOLVE(eglBindAPI);
    RESOLVE(eglChooseConfig);
    RESOLVE(eglCreateContext);
    RESOLVE(eglCreatePbufferSurface);
    RESOLVE(eglCreateWindowSurface);
    RESOLVE(eglDestroyContext);
    RESOLVE(eglDestroySurface);
    RESOLVE(eglGetConfigAttrib);
    RESOLVE(eglGetCurrentContext);
    RESOLVE(eglGetDisplay);
    RESOLVE(eglGetError);
    RESOLVE(eglInitialize);
    RESOLVE(eglMakeCurrent);
    RESOLVE(eglSwapBuffers);
    RESOLVE(eglReleaseThread);
    RESOLVE(eglSwapInterval);
    RESOLVE(eglTerminate);
    RESOLVE(eglGetCurrentSurface);
    RESOLVE(eglQuerySurface);
#undef RESOLVE
    eglBindAPI_p = candidate_eglBindAPI;
    eglChooseConfig_p = candidate_eglChooseConfig;
    eglCreateContext_p = candidate_eglCreateContext;
    eglCreatePbufferSurface_p = candidate_eglCreatePbufferSurface;
    eglCreateWindowSurface_p = candidate_eglCreateWindowSurface;
    eglDestroyContext_p = candidate_eglDestroyContext;
    eglDestroySurface_p = candidate_eglDestroySurface;
    eglGetConfigAttrib_p = candidate_eglGetConfigAttrib;
    eglGetCurrentContext_p = candidate_eglGetCurrentContext;
    eglGetDisplay_p = candidate_eglGetDisplay;
    eglGetError_p = candidate_eglGetError;
    eglInitialize_p = candidate_eglInitialize;
    eglMakeCurrent_p = candidate_eglMakeCurrent;
    eglSwapBuffers_p = candidate_eglSwapBuffers;
    eglReleaseThread_p = candidate_eglReleaseThread;
    eglSwapInterval_p = candidate_eglSwapInterval;
    eglTerminate_p = candidate_eglTerminate;
    eglGetCurrentSurface_p = candidate_eglGetCurrentSurface;
    eglQuerySurface_p = candidate_eglQuerySurface;
    provider_handle = handle;
    fprintf(stderr, "EGLBridge: verified provider '%s'\n", provider);
    return true;
failed:
    eglGetProcAddress_p = NULL;
    dlclose(handle);
    return false;
}

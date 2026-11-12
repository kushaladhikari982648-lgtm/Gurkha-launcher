#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdbool.h>
#include <environ/environ.h>
#include "gl_bridge.h"
#include "egl_loader.h"
#include "context_policy.h"
#include "window_slot.h"

static const char* g_LogTag = "GLBridge";
static __thread gl_render_window_t* currentBundle;
static EGLDisplay g_EglDisplay = EGL_NO_DISPLAY;
static bool initialized;

static void gl_failure(const char *stage, EGLint error) {
    char message[192];
    snprintf(message, sizeof(message), "EGLBridge: %s failed (EGL 0x%04x); no GL calls are safe", stage, error);
    __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "%s", message);
#ifndef GURKHA_BRIDGE_TEST
    /* This runs on the calling HotSpot render thread, not a cached ART JNIEnv. */
    JavaVM *vm = pojav_environ->runtimeJavaVMPtr;
    JNIEnv *env = NULL;
    if (vm && (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_4) == JNI_OK && env && !(*env)->ExceptionCheck(env)) {
        jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (type) { (*env)->ThrowNew(env, type, message); (*env)->DeleteLocalRef(env, type); }
    }
#endif
}

bool gl_init() {
    if (initialized) return true;
    if (!dlsym_EGL()) return false;
    g_EglDisplay = eglGetDisplay_p(EGL_DEFAULT_DISPLAY);
    if (g_EglDisplay == EGL_NO_DISPLAY || !eglInitialize_p(g_EglDisplay, NULL, NULL)) {
        gl_failure("display initialization", eglGetError_p());
        return false;
    }
    initialized = true;
    return true;
}

gl_render_window_t* gl_get_current() { return currentBundle; }

static void gl4esi_get_display_dimensions(int* width, int* height) {
    *width = *height = 0;
    if (!currentBundle || currentBundle->surface == EGL_NO_SURFACE) return;
    if (!eglQuerySurface_p(g_EglDisplay, currentBundle->surface, EGL_WIDTH, width) ||
        !eglQuerySurface_p(g_EglDisplay, currentBundle->surface, EGL_HEIGHT, height)) *width = *height = 0;
}

gl_render_window_t* gl_init_context(gl_render_window_t *share) {
    if (!initialized) return NULL;
    int es;
    if (!bridge_es_version(getenv("LIBGL_ES"), &es)) {
        gl_failure("invalid LIBGL_ES (expected 1, 2 or 3)", EGL_BAD_ATTRIBUTE);
        return NULL;
    }
    const char *renderer = getenv("POJAV_RENDERER");
    bool desktop = renderer && !strncmp(renderer, "opengles3_desktopgl", 19);
    EGLint renderable = desktop ? EGL_OPENGL_BIT : es >= 3 ? EGL_OPENGL_ES3_BIT_KHR :
                        es == 2 ? EGL_OPENGL_ES2_BIT : EGL_OPENGL_ES_BIT;
    const EGLint attrs[] = { EGL_BLUE_SIZE,8, EGL_GREEN_SIZE,8, EGL_RED_SIZE,8,
        EGL_ALPHA_SIZE,8, EGL_DEPTH_SIZE,24, EGL_SURFACE_TYPE,EGL_WINDOW_BIT|EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE,renderable, EGL_NONE };
    gl_render_window_t *bundle = calloc(1, sizeof(*bundle));
    if (!bundle) { gl_failure("allocate context bundle", EGL_BAD_ALLOC); return NULL; }
    EGLint count = 0;
    const char *stage = "bind API";
    if (!eglBindAPI_p(desktop ? EGL_OPENGL_API : EGL_OPENGL_ES_API)) goto failed;
    stage = "choose matching config";
    if (!eglChooseConfig_p(g_EglDisplay, attrs, &bundle->config, 1, &count)) goto failed;
    if (count < 1) { gl_failure("no matching EGL config", EGL_BAD_CONFIG); free(bundle); return NULL; }
    stage = "query native visual";
    if (!eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_NATIVE_VISUAL_ID, &bundle->format)) goto failed;
    const EGLint context_attrs[] = { EGL_CONTEXT_CLIENT_VERSION, es, EGL_NONE };
    stage = "create context";
    bundle->context = eglCreateContext_p(g_EglDisplay, bundle->config,
        share ? share->context : EGL_NO_CONTEXT, desktop ? NULL : context_attrs);
    if (bundle->context == EGL_NO_CONTEXT) goto failed;
    return bundle;
failed:
    gl_failure(stage, eglGetError_p());
    free(bundle);
    return NULL;
}

/* Render thread only. Snapshot takes ownership without holding the slot mutex in EGL. */
static bool replace_surface(gl_render_window_t *bundle, bool detached) {
    uint64_t generation;
    ANativeWindow *next = bridge_acquire_window(&generation);
    if (detached && next) { ANativeWindow_release(next); next = NULL; }
    if (!eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)) {
        if (next) ANativeWindow_release(next);
        gl_failure("unbind surface", eglGetError_p());
        return false;
    }
    currentBundle = NULL;
    if (bundle->surface != EGL_NO_SURFACE) eglDestroySurface_p(g_EglDisplay, bundle->surface);
    bundle->surface = EGL_NO_SURFACE;
    if (bundle->nativeSurface) ANativeWindow_release(bundle->nativeSurface);
    bundle->nativeSurface = next;
    bundle->window_generation = generation;
    bundle->has_window_generation = true;
    if (next) {
        if (ANativeWindow_setBuffersGeometry(next, 0, 0, bundle->format) != 0) {
            gl_failure("native window geometry", EGL_BAD_NATIVE_WINDOW);
            return false;
        }
        bundle->surface = eglCreateWindowSurface_p(g_EglDisplay, bundle->config, next, NULL);
    } else {
        const EGLint attrs[] = {EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
        bundle->surface = eglCreatePbufferSurface_p(g_EglDisplay, bundle->config, attrs);
    }
    if (bundle->surface == EGL_NO_SURFACE) { gl_failure("create surface", eglGetError_p()); return false; }
    return true;
}

void gl_make_current(gl_render_window_t* bundle) {
    if (!initialized) { gl_failure("bridge not initialized", EGL_NOT_INITIALIZED); return; }
    if (!bundle) {
        if (eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)) currentBundle = NULL;
        else gl_failure("detach context", eglGetError_p());
        return;
    }
    bool main = !pojav_environ->mainWindowBundle || pojav_environ->mainWindowBundle == (basic_render_window_t*)bundle;
    if (bundle->surface == EGL_NO_SURFACE || (main && bundle->window_generation != bridge_window_generation())) {
        if (!replace_surface(bundle, !main)) return;
    }
    if (!eglMakeCurrent_p(g_EglDisplay, bundle->surface, bundle->surface, bundle->context)) {
        currentBundle = NULL;
        gl_failure("make current", eglGetError_p());
        return;
    }
    currentBundle = bundle;
    if (main) pojav_environ->mainWindowBundle = (basic_render_window_t*)bundle;
}

void gl_swap_buffers() {
    gl_render_window_t *bundle = currentBundle;
    if (!bundle) { gl_failure("swap without current context", EGL_BAD_CONTEXT); return; }
    if (pojav_environ->mainWindowBundle == (basic_render_window_t*)bundle &&
        bundle->window_generation != bridge_window_generation()) {
        gl_make_current(bundle);
        if (!currentBundle) return;
    }
    if (!eglSwapBuffers_p(g_EglDisplay, bundle->surface)) {
        EGLint error = eglGetError_p();
        if (error == EGL_BAD_SURFACE) {
            if (replace_surface(bundle, true)) gl_make_current(bundle);
        } else {
            currentBundle = NULL;
            gl_failure("swap (context/resources may be lost)", error);
        }
    }
}

/* Window callbacks publish to window_slot, never touch render-thread context state. */
void gl_setup_window() {}
void gl_swap_interval(int interval) {
    if (!initialized || !currentBundle) return;
    if (!eglSwapInterval_p(g_EglDisplay, pojav_environ->force_vsync ? 1 : interval))
        gl_failure("swap interval", eglGetError_p());
}
void gl_terminate(void) {
    if (!initialized) return;
    gl_render_window_t *bundle = currentBundle;
    if (!eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)) {
        gl_failure("terminate detach", eglGetError_p()); return;
    }
    currentBundle = NULL;
    if (bundle) {
        if (bundle->surface != EGL_NO_SURFACE) eglDestroySurface_p(g_EglDisplay, bundle->surface);
        eglDestroyContext_p(g_EglDisplay, bundle->context);
        if (bundle->nativeSurface) ANativeWindow_release(bundle->nativeSurface);
        if (pojav_environ->mainWindowBundle == (basic_render_window_t*)bundle) pojav_environ->mainWindowBundle = NULL;
        free(bundle);
    }
    /* Do not eglTerminate the process-wide display used by Android/SDL/recording. */
    eglReleaseThread_p();
}

#ifndef GURKHA_BRIDGE_TEST
JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_PojavRendererInit_nativeInitGl4esInternals(JNIEnv *env, jclass clazz,
                                                            jobject function_provider) {
    __android_log_print(ANDROID_LOG_INFO, g_LogTag, "GL4ES internals initializing...");
    jclass funcProviderClass = (*env)->GetObjectClass(env, function_provider);
    jmethodID method_getFunctionAddress = (*env)->GetMethodID(env, funcProviderClass, "getFunctionAddress", "(Ljava/lang/CharSequence;)J");
#define GETSYM(N) ((*env)->CallLongMethod(env, function_provider, method_getFunctionAddress, (*env)->NewStringUTF(env, N)));

    void (*set_getmainfbsize)(void (*new_getMainFBSize)(int* width, int* height)) = (void*)GETSYM("set_getmainfbsize");
    if(set_getmainfbsize != NULL) {
        __android_log_print(ANDROID_LOG_INFO, g_LogTag, "GL4ES internals initialized dimension callback");
        set_getmainfbsize(gl4esi_get_display_dimensions);
    }

#undef GETSYM
}


#endif

#include <jni.h>
#include <assert.h>
#include <dlfcn.h>

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <unistd.h>

#include <EGL/egl.h>
#include <GL/osmesa.h>
#include "ctxbridges/egl_loader.h"
#include "ctxbridges/osmesa_loader.h"
#include "ctxbridges/renderer_config.h"
#include "driver_helper/nsbypass.h"

#ifdef GLES_TEST
#include <GLES2/gl2.h>
#endif

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/rect.h>
#include <string.h>
#include <environ/environ.h>
#include <android/dlext.h>
#include <time.h>
#include "utils.h"
#include "ctxbridges/bridge_tbl.h"
#include "ctxbridges/osm_bridge.h"
#include "ctxbridges/window_slot.h"
#include "ctxbridges/context_policy.h"

#define GLFW_CLIENT_API 0x22001
/* Consider GLFW_NO_API as Vulkan API */
#define GLFW_NO_API 0
#define GLFW_OPENGL_API 0x30001

// This means that the function is an external API and that it will be used
#define EXTERNAL_API __attribute__((used))
// This means that you are forced to have this function/variable for ABI compatibility
#define ABI_COMPAT __attribute__((unused))

EGLConfig config;
struct PotatoBridge potatoBridge;

void* loadTurnipVulkan();
void calculateFPS();
extern jboolean ensureGlfwRuntimeBridgeInitialized(JNIEnv *env);

EXTERNAL_API void pojavTerminate() {
    printf("EGLBridge: Terminating\n");

    if (bridge_is_sdl()) return; // SDL owns its contexts and surfaces.
    if (pojav_environ->config_renderer == RENDERER_GL4ES) gl_terminate();
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeWindow(JNIEnv* env, ABI_COMPAT jclass clazz, jobject surface) {
    ANativeWindow *window = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    if ((*env)->ExceptionCheck(env)) return;
    /* fromSurface already acquired one reference. The slot takes ownership. */
    bridge_publish_window(window);
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_releaseBridgeWindow(ABI_COMPAT JNIEnv *env, ABI_COMPAT jclass clazz) {
    bridge_publish_window(NULL); // idempotent; render thread consumes the generation change
}

EXTERNAL_API void* pojavGetCurrentContext() {
    return !bridge_is_sdl() && br_get_current ? br_get_current() : NULL;
}

static void set_vulkan_ptr(void* ptr) {
    char envval[64];
    sprintf(envval, "%"PRIxPTR, (uintptr_t)ptr);
    setenv("VULKAN_PTR", envval, 1);
}

void load_vulkan() {
    const char* zinkPreferSystemDriver = getenv("POJAV_ZINK_PREFER_SYSTEM_DRIVER");
    int deviceApiLevel = android_get_device_api_level();
    if (zinkPreferSystemDriver == NULL && deviceApiLevel >= 28) {
#ifdef ADRENO_POSSIBLE
        void* result = loadTurnipVulkan();
        if (result != NULL)
        {
            printf("AdrenoSupp: Loaded Turnip, loader address: %p\n", result);
            set_vulkan_ptr(result);
            return;
        }
#endif
    }

    printf("OSMDroid: Loading Vulkan regularly...\n");
    void* vulkanPtr = dlopen("libvulkan.so", RTLD_LAZY | RTLD_LOCAL);
    printf("OSMDroid: Loaded Vulkan, ptr=%p\n", vulkanPtr);
    set_vulkan_ptr(vulkanPtr);
}

int pojavInitOpenGL() {
    if (bridge_is_sdl()) {
        fprintf(stderr, "EGLBridge: GLFW initialization refused on SDL backend\n");
        return 0;
    }
    const char *forceVsync = getenv("FORCE_VSYNC");
    pojav_environ->force_vsync = forceVsync && !strcmp(forceVsync, "true");
    const char *renderer = getenv("POJAV_RENDERER");
    if (!renderer) { fprintf(stderr, "EGLBridge: no renderer selected\n"); return 0; }
    if (!strncmp("opengles", renderer, 8)) {
        pojav_environ->config_renderer = RENDERER_GL4ES;
        set_gl_bridge_tbl();
    } else if (!strcmp(renderer, "custom_gallium") || !strcmp(renderer, "vulkan_zink")) {
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        if (!strcmp(renderer, "vulkan_zink")) {
            load_vulkan();
            setenv("GALLIUM_DRIVER", "zink", 1);
        }
        set_osm_bridge_tbl();
    } else {
        fprintf(stderr, "EGLBridge: unsupported renderer '%s'\n", renderer);
        return 0;
    }
    if (!br_init || !br_init()) {
        fprintf(stderr, "EGLBridge: initialization failed for '%s'\n", renderer);
        br_init_context = NULL; br_make_current = NULL; br_get_current = NULL;
        br_swap_buffers = NULL; br_swap_interval = NULL;
        return 0;
    }
    return 1;
}

EXTERNAL_API int pojavInit() {
    if (bridge_is_sdl()) return 0;
    if (pojav_environ->runtimeJavaVMPtr != NULL) {
        JNIEnv *jvm_env = NULL;
        jint env_result = (*pojav_environ->runtimeJavaVMPtr)->GetEnv(
                pojav_environ->runtimeJavaVMPtr, (void**) &jvm_env, JNI_VERSION_1_4);
        if (env_result == JNI_EDETACHED) {
            env_result = (*pojav_environ->runtimeJavaVMPtr)->AttachCurrentThread(
                    pojav_environ->runtimeJavaVMPtr, &jvm_env, NULL);
        }
        if (env_result == JNI_OK && jvm_env != NULL) {
            pojav_environ->runtimeJNIEnvPtr_JRE = jvm_env;
        }
    }
    if (!ensureGlfwRuntimeBridgeInitialized(pojav_environ->runtimeJNIEnvPtr_JRE)) {
        fprintf(stderr, "EGLBridge: GLFW runtime bridge is not initialized\n");
        return 0;
    }
    ANativeWindow *window = bridge_acquire_window(NULL);
    if (!window) { fprintf(stderr, "EGLBridge: no Android surface\n"); return 0; }
    pojav_environ->savedWidth = ANativeWindow_getWidth(window);
    pojav_environ->savedHeight = ANativeWindow_getHeight(window);
    bool valid = pojav_environ->savedWidth > 0 && pojav_environ->savedHeight > 0;
    ANativeWindow_release(window);
    return valid ? pojavInitOpenGL() : 0;
}

EXTERNAL_API void pojavSetWindowHint(int hint, int value) {
    if (bridge_is_sdl() || hint != GLFW_CLIENT_API) return;
    switch (value) {
        case GLFW_NO_API:
            pojav_environ->config_renderer = RENDERER_VULKAN;
            /* Nothing to do: initialization is handled in Java-side */
            // pojavInitVulkan();
            break;
        case GLFW_OPENGL_API:
            /* Nothing to do: initialization is called in pojavCreateContext */
            // pojavInitOpenGL();
            break;
        default:
            printf("GLFW: Unimplemented API 0x%x\n", value);
            abort();
    }
}

EXTERNAL_API void pojavSwapBuffers() {
    if (bridge_is_sdl() || !br_swap_buffers) return;
    calculateFPS();

    if (pojav_environ->config_renderer == RENDERER_VK_ZINK
     || pojav_environ->config_renderer == RENDERER_GL4ES)
    {
        br_swap_buffers();
    }

}

EXTERNAL_API void pojavMakeCurrent(void* window) {
    if (bridge_is_sdl() || !br_make_current) return;
    if (pojav_environ->config_renderer == RENDERER_VK_ZINK
     || pojav_environ->config_renderer == RENDERER_GL4ES)
    {
        br_make_current((basic_render_window_t*)window);
    }

}

EXTERNAL_API void* pojavCreateContext(void* contextSrc) {
    if (bridge_is_sdl()) return NULL;
    if (pojav_environ->config_renderer == RENDERER_VULKAN) {
        /* Direct Vulkan owns this reference until process exit (legacy GLFW ABI). */
        return bridge_acquire_window(NULL);
    }
    if (!br_init_context) return NULL;
    return br_init_context((basic_render_window_t*)contextSrc);
}

void* maybe_load_vulkan() {
    // We use the env var because
    // 1. it's easier to do that
    // 2. it won't break if something will try to load vulkan and osmesa simultaneously
    if(getenv("VULKAN_PTR") == NULL) load_vulkan();
    return (void*) strtoul(getenv("VULKAN_PTR"), NULL, 0x10);
}

static int frameCount = 0;
static int fps = 0;
static time_t lastTime = 0;

void calculateFPS() {
    frameCount++;
    time_t currentTime = time(NULL);

    if (currentTime != lastTime) {
        lastTime = currentTime;
        fps = frameCount;
        frameCount = 0;
    }
}

EXTERNAL_API JNIEXPORT jint JNICALL
Java_org_lwjgl_glfw_CallbackBridge_getCurrentFps(JNIEnv *env, jclass clazz) {
    return fps;
}

EXTERNAL_API JNIEXPORT jlong JNICALL
Java_org_lwjgl_vulkan_VK_getVulkanDriverHandle(ABI_COMPAT JNIEnv *env, ABI_COMPAT jclass thiz) {
    printf("EGLBridge: LWJGL-side Vulkan loader requested the Vulkan handle\n");
    return (jlong) maybe_load_vulkan();
}

EXTERNAL_API void pojavSwapInterval(int interval) {
    if (bridge_is_sdl() || !br_swap_interval) return;
    if (pojav_environ->config_renderer == RENDERER_VK_ZINK
     || pojav_environ->config_renderer == RENDERER_GL4ES)
    {
        br_swap_interval(interval);
    }

}


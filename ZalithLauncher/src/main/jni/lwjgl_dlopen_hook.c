//
// Created by maks on 06.01.2025.
//

#include <android/api-level.h>
#include <android/log.h>
#include <jni.h>

#include <environ/environ.h>

#include <dlfcn.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>

extern void* maybe_load_vulkan();
typedef void* (*SdlDlsymProxyFn)(void* handle, const char* name, void* realPtr);

static SdlDlsymProxyFn resolveSdlDlsymProxy(void) {
    static SdlDlsymProxyFn cached = NULL;
    if (cached != NULL) return cached;
    void* exithook = dlopen("libexithook.so", RTLD_LAZY | RTLD_NOLOAD);
    if (exithook == NULL) {
        exithook = dlopen("libexithook.so", RTLD_LAZY | RTLD_LOCAL);
    }
    if (exithook != NULL) {
        cached = (SdlDlsymProxyFn) dlsym(exithook, "sdlDlsymProxy");
    }
    return cached;
}

/**
 * Basically a verbatim implementation of ndlopen(), found at
 * https://github.com/PojavLauncherTeam/lwjgl3/blob/3.3.1/modules/lwjgl/core/src/generated/c/linux/org_lwjgl_system_linux_DynamicLinkLoader.c#L11
 * but with our own additions for stuff like vulkanmod.
 */
static jlong ndlopen_bugfix(__attribute__((unused)) JNIEnv *env,
                            __attribute__((unused)) jclass class,
                            jlong filename_ptr,
                            jint jmode) {
    const char* filename = (const char*) (uintptr_t) filename_ptr;

    // Oveeride vulkan loading to let us load vulkan ourselves
    if(filename && strcmp(filename, "libvulkan.so") == 0) {
        printf("LWJGL linkerhook: replacing load for libvulkan.so with custom driver\n");
        return (jlong) (uintptr_t) maybe_load_vulkan();
    }

    if (filename != NULL) {
        size_t len = strlen(filename);
        if (strcmp(filename, "libglfw.so") == 0 ||
            (len >= 11 && strcmp(filename + len - 11, "/libglfw.so") == 0)) {
            void* handle = dlopen("libpojavexec.so", (int) jmode | RTLD_NOLOAD);
            if (handle == NULL) {
                handle = dlopen("libpojavexec.so", (int) jmode);
            }
            if (handle != NULL) {
                return (jlong) (uintptr_t) handle;
            }
        }
    }

    // This hook also serves the task of mitigating a bug: the idea is that since, on Android 10 and
    // earlier, the linker doesn't really do namespace nesting.
    // It is not a problem as most of the libraries are in the launcher path, but when you try to run
    // VulkanMod which loads shaderc outside of the default jni libs directory through this method,
    // it can't load it because the path is not in the allowed paths for the anonymous namesapce.
    // This method fixes the issue by being in libpojavexec, and thus being in the classloader namespace

    int mode = (int)jmode;
    return (jlong) (uintptr_t) dlopen(filename, mode);
}

static jlong ndlsym_bugfix(__attribute__((unused)) JNIEnv *env,
                           __attribute__((unused)) jclass class,
                           jlong handle_ptr,
                           jlong name_ptr) {
    void* handle = (void*) (uintptr_t) handle_ptr;
    const char* name = (const char*) (uintptr_t) name_ptr;
    void* symbol = dlsym(handle, name);
    SdlDlsymProxyFn proxy = resolveSdlDlsymProxy();
    if (proxy != NULL) {
        symbol = proxy(handle, name, symbol);
    }
    return (jlong) (uintptr_t) symbol;
}

/**
 * Install the LWJGL dlopen/dlsym hook. This allows us to mitigate linker bugs, add custom library overrides,
 * and route LWJGL SDL3 function lookups through sdlDlsymProxy.
 */
void installLwjglDlopenHook() {
    __android_log_print(ANDROID_LOG_INFO, "LwjglLinkerHook", "Installing LWJGL dlopen()/dlsym() hooks");
    JNIEnv* env = pojav_environ->runtimeJNIEnvPtr_JRE;
    jclass dynamicLinkLoader = (*env)->FindClass(env, "org/lwjgl/system/linux/DynamicLinkLoader");
    if(dynamicLinkLoader == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "LwjglLinkerHook", "Failed to find the target class");
        (*env)->ExceptionClear(env);
        return;
    }
    JNINativeMethod methods[] = {
            {"ndlopen", "(JI)J", &ndlopen_bugfix},
            {"ndlsym", "(JJ)J", &ndlsym_bugfix}
    };
    if((*env)->RegisterNatives(env, dynamicLinkLoader, methods, 2) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "LwjglLinkerHook", "Failed to register the hooked methods");
        (*env)->ExceptionClear(env);
    }
}
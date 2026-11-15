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

static void* dlopen_with_lwjgl_natives_fallback(const char* lib_name, int mode) {
    if (!lib_name || !lib_name[0]) return NULL;
    const char* natives_dir = getenv("POJAV_LWJGL_NATIVES_DIR");
    if (natives_dir && natives_dir[0]) {
        char candidate[512];
        snprintf(candidate, sizeof(candidate), "%s/%s", natives_dir, lib_name);
        void* handle = dlopen(candidate, mode);
        if (handle != NULL) return handle;
    }
    return dlopen(lib_name, mode);
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
    int mode = (int)jmode;

    if (filename != NULL) {
        const char* slash = strrchr(filename, '/');
        const char* base = slash ? (slash + 1) : filename;

        // Override vulkan loading to let us load vulkan ourselves (libvulkan.so or libvulkan.so.1)
        if (strstr(base, "libvulkan.so") == base) {
            printf("LWJGL linkerhook: replacing load for %s with custom driver\n", filename);
            void* vk = maybe_load_vulkan();
            if (vk != NULL) {
                return (jlong) (uintptr_t) vk;
            }
        }

        if (strcmp(filename, "libglfw.so") == 0 || strstr(base, "libglfw.so") == base) {
            void* handle = dlopen("libpojavexec.so", mode | RTLD_NOLOAD);
            if (handle == NULL) {
                handle = dlopen("libpojavexec.so", mode);
            }
            if (handle != NULL) {
                return (jlong) (uintptr_t) handle;
            }
        }

        // Redirect desktop/mod-bundled shaderc, vma, and spirv-cross libraries to Android native builds
        if (strstr(base, "libshaderc") == base || strstr(base, "liblwjgl_shaderc") == base) {
            void* handle = dlopen_with_lwjgl_natives_fallback("libshaderc.so", mode);
            if (handle != NULL) {
                printf("LWJGL linkerhook: redirected %s -> libshaderc.so\n", filename);
                return (jlong) (uintptr_t) handle;
            }
        }
        if (strstr(base, "liblwjgl_vma") == base) {
            void* handle = dlopen_with_lwjgl_natives_fallback("liblwjgl_vma.so", mode);
            if (handle != NULL) {
                printf("LWJGL linkerhook: redirected %s -> liblwjgl_vma.so\n", filename);
                return (jlong) (uintptr_t) handle;
            }
        }
        if (strstr(base, "libspirv-cross") == base || strstr(base, "liblwjgl_spvc") == base) {
            void* handle = dlopen_with_lwjgl_natives_fallback("libspirv-cross-c-shared.so", mode);
            if (handle != NULL) {
                printf("LWJGL linkerhook: redirected %s -> libspirv-cross-c-shared.so\n", filename);
                return (jlong) (uintptr_t) handle;
            }
        }

        void* direct = dlopen(filename, mode);
        if (direct != NULL) {
            return (jlong) (uintptr_t) direct;
        }
        if (slash != NULL && base[0] != '\0') {
            void* fallback = dlopen_with_lwjgl_natives_fallback(base, mode);
            if (fallback != NULL) {
                printf("LWJGL linkerhook: fallback loaded %s via %s\n", filename, base);
                return (jlong) (uintptr_t) fallback;
            }
        }
        return 0;
    }

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
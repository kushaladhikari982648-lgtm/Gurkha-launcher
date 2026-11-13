//
// Intercepts dlsym lookups for SDL3 symbols so LWJGL's function-pointer table uses our SDL3 proxies.
//

#include "sdl_hook.h"

#include <android/log.h>
#include <bytehook.h>
#include <dlfcn.h>
#include <stddef.h>

#define TAG "SdlDlopenHook"

typedef void* (*DlsymFn)(void*, const char*);

static void* customDlsym(void* handle, const char* symbol) {
    BYTEHOOK_STACK_SCOPE();
    DlsymFn prev = BYTEHOOK_GET_PREV(customDlsym);
    void* realSymbol = prev != NULL ? prev(handle, symbol) : dlsym(handle, symbol);
    return sdlDlsymProxy(handle, symbol, realSymbol);
}

void create_sdl_dlopen_hooks(void* bytehook_hook_all_p) {
    if (bytehook_hook_all_p == NULL) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "bytehook_hook_all symbol is null");
        return;
    }

    bytehook_stub_t (*bytehook_hook_all_fn)(
        const char* caller_path_name,
        const char* sym_name,
        void* new_func,
        bytehook_hooked_t hooked,
        void* hooked_arg
    ) = bytehook_hook_all_p;

    bytehook_stub_t stub = bytehook_hook_all_fn(NULL, "dlsym", (void*) &customDlsym, NULL, NULL);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Installed dlsym hook for SDL symbols, stub=%p", stub);
}

//
// SDL3 native hooks for single-window Android reuse, OpenGL ES compatibility, and dlsym interception.
//

#ifndef ZALITHLAUNCHER_SDL_HOOK_H
#define ZALITHLAUNCHER_SDL_HOOK_H

#ifdef __cplusplus
extern "C" {
#endif

void create_sdl_hooks(void* bytehook_hook_all_p);
void create_sdl_dlopen_hooks(void* bytehook_hook_all_p);
void* sdlDlsymProxy(void* handle, const char* name, void* realPtr);

#ifdef __cplusplus
}
#endif

#endif // ZALITHLAUNCHER_SDL_HOOK_H

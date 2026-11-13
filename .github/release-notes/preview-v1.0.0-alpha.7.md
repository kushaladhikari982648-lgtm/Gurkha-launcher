## Gurkha Launcher 1.0.0-alpha.7

### Fixes for Minecraft 26.x OpenGL 3.3 Context Verification & Single-Window SDL3
- **Fixed `BackendCreationException: Failed to create OpenGL 3.3 context, got OpenGL 3.0` (`GlDevice.<init>(GlDevice.java:102)`)**:
  - Updated SDL3 `SDL_GLAttr` constants in `sdl_hook.c` (`SDL_GL_CONTEXT_MAJOR_VERSION = 17`, `SDL_GL_CONTEXT_MINOR_VERSION = 18`, `SDL_GL_CONTEXT_FLAGS = 19`, `SDL_GL_CONTEXT_PROFILE_MASK = 20`) to match `libSDL3.so`'s `SDL_GL_SetAttribute` / `SDL_GL_GetAttribute` jump tables.
  - Preserved `SDL_GL_CONTEXT_MINOR_VERSION = 3` in `SDL_GL_SetAttribute` and `prepareGlesBeforeCreateWindow` instead of clamping minor version to `0` (since `SDL_GL_GetAttribute` in `libSDL3.so` reads `_this->gl_config.minor_version` directly, while `EGL_CONTEXT_MINOR_VERSION_KHR` `0x30FB` is stripped inside `proxyEglCreateContext` and `ltw_sdl_eglCreateContext`).
  - Added `SDL_GL_GetAttribute` ByteHook and `dlsym` proxy hooks so `SDL_GL_CONTEXT_MAJOR_VERSION` (`17`) and `SDL_GL_CONTEXT_MINOR_VERSION` (`18`) reliably report `>= 3.3` and `SDL_GL_CONTEXT_PROFILE_MASK` (`20`) reports `SDL_GL_CONTEXT_PROFILE_CORE` (`1`).
- **Fixed post-exception `SIGSEGV` in `SDL_ShowSimpleMessageBox` / `SDL_ShowMessageBox`**:
  - Hooked `SDL_ShowSimpleMessageBox` and `SDL_ShowMessageBox` via ByteHook and `sdlDlsymProxy` so error dialogs called from the OpenJDK HotSpot `Render thread` log directly to `stderr` / `latestlog.txt` and `ErrorActivity` instead of calling `AttachCurrentThread` on Android's ART `JavaVM`.

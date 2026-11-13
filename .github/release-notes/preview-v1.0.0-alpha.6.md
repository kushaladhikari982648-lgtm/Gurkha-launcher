# Gurkha Launcher 1.0.0-alpha.6 — Single-Window Android SDL3 Reuse & OpenGL Initialization Fixes on Non-Vulkan Devices

**Debug-key-signed alpha release**, package `com.gurkha.launcher.debug`, version code `100016`.
Uses the same development signing key as prior preview builds. Back up worlds and settings before installing.

## Downloads
- **armeabi-v7a**: 32-bit ARM Android devices (including TECNO BB4k / Helio A22 / PowerVR GE8300).
- **arm64-v8a**: 64-bit ARM Android devices.
- **universal**: all supported architectures, if unsure (larger download).
- Separate x86 and x86_64 APKs are also attached.

## Fixes in 1.0.0-alpha.6
- **Single-window Android SDL3 primary-window reuse (`sdl_hook.c`, `sdl_dlopen_hook.c`, `lwjgl_dlopen_hook.c`, `exit_hook.c`):**
  - Upstream Android `libSDL3.so` allows only one native `Android_Window` (`"Android only supports one window"`), while Minecraft 26.3+ (`RenderPearl`) creates a hidden utility `SDL_Window` (with the OpenGL context attached) before creating the main game `SDL_Window`.
  - Intercepts SDL3 window lifecycle and event lookups via both `bytehook` and LWJGL's `DynamicLinkLoader.ndlsym`, reusing and reference-counting the primary `SDL_Window` across `SDL_CreateWindow`, `SDL_CreateWindowWithProperties`, and `SDL_DestroyWindow`, showing and updating the window title when the main visible window reuses the hidden utility window, preventing `SDL_HideWindow` on the utility window from unfocusing the shared main window, and making `SDL_SetWindowSize` succeed on Android SDL3.
- **Fixed Vulkan-probe to OpenGL window transition on devices without Vulkan support (`sdl_hook.c`, `GraphicsBackend.kt`):**
  - On Android 7+ devices without a Vulkan hardware driver, `/system/lib/libvulkan.so` is still present in the system image, so a Vulkan probe window (`SDL_WINDOW_VULKAN`) can be created without `SDL_WINDOW_OPENGL` or an `egl_surface` before `vkEnumeratePhysicalDevices` fails and Minecraft falls back to OpenGL.
  - Automatically detects missing hardware Vulkan support via `PackageManager` (`FEATURE_VULKAN_HARDWARE_LEVEL` / `FEATURE_VULKAN_HARDWARE_VERSION`), forces `SDL_WINDOW_OPENGL` (stripping `SDL_WINDOW_VULKAN`) when OpenGL is selected, and automatically destroys any non-OpenGL primary window before creating the OpenGL window so `SDL_GL_CreateContext` never fails with `"Specified window is not an OpenGL window"`.
- **Fixed OpenGL ES context & config initialization under SDL3 (`sdl_hook.c`, `ltw_sdl_egl.c`):**
  - Normalizes `SDL_GL_SetAttribute` (`SDL_GL_CONTEXT_PROFILE_MASK = SDL_GL_CONTEXT_PROFILE_ES`, clears `SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG`, clamps version to GLES 3.0) and always strips desktop-only KHR attributes (`0x30FB`, `0x30FC`, `0x30FD`, `0x31B3`, `0x309D`) in both `sdl_hook.c` and `libltw_sdl_egl.so`, with `EGL_OPENGL_ES3_BIT_KHR` -> `EGL_OPENGL_ES2_BIT` config fallback and `SDL_GL_MakeCurrent` surfaceless fallback to the primary window.
- **Fixed `options.txt` / `MCOptions` overwriting `preferredGraphicsBackend` (`GraphicsBackend.kt`, `MCOptions.kt`, `MinecraftGLSurface.java`, `assets/options.txt`):**
  - Ensures `GraphicsBackend.writeOption` creates `options.txt` if missing, sets both `preferredGraphicsBackend:"opengl"` and `startedCleanly:true`, and syncs `MCOptions` in memory so `MCOptions.save()` during surface startup never strips `preferredGraphicsBackend`.
- **Fixed SDL3 `Android_JNI_ShowMessageBox` `NoSuchMethodError` (`MainActivity.java`, `SDLActivity.java`):**
  - Adds `@Keep public int messageboxShowMessageBox(...)` on `MainActivity` delegating to `SDLActivity` with the active `Activity` context and logging the dialog text to `latestlog.txt`.

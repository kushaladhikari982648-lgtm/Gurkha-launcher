# Gurkha Launcher 1.0.0-alpha.5 — Minecraft 26.3 ARM32/SDL3 launch crash fixes & Video Graphics API switch

**Debug-key-signed alpha release**, package `com.gurkha.launcher.debug`, version code `100015`.
Uses the same development signing key as prior preview builds. Back up worlds and settings before installing.

## Downloads
- **armeabi-v7a**: 32-bit ARM Android devices (including TECNO BB4k / Helio A22 / PowerVR GE8300).
- **arm64-v8a**: 64-bit ARM Android devices.
- **universal**: all supported architectures, if unsure (larger download).
- Separate x86 and x86_64 APKs are also attached.

## Fixes in 1.0.0-alpha.5
- **Fixed FreeType 2.13 symbol shadowing on LWJGL 3.4.1 / Minecraft 26.x (`JREUtils.initJavaRuntime`):**
  Preloads the modern launcher `libfreetype.so` instead of the older `jre/lib/libfreetype.so` with `RTLD_GLOBAL` and skips `libfreetype.so` / `libjsig.so` in `locateLibs`, preventing `UnsatisfiedLinkError` / missing `FT_Get_Transform` & `FT_Get_Color_Glyph_Paint` relocations when Minecraft 26.3 initializes `org.lwjgl.util.freetype.FreeType`.
- **Fixed 32-bit ARM native C stack overflow in LTW GLSL shader compilation (`getArm32FixArgs`):**
  Sets `-Xss1024k` by default on 32-bit ARM where OpenJDK 25 defaults `ThreadStackSize` to 320 KB (~224 KB usable after HotSpot guard pages) while `libltw.so`'s `_mesa_glsl_compile_shader` + `_mesa_glsl_parse` allocate >148 KB of contiguous C stack frames.
- **Fixed OpenAL native library lookup on SDL3 / LWJGL 3.4.1 (`SdlSupport.getJvmArgs` & `JREUtils`):**
  Includes `nativeLibDir` in `-Dorg.lwjgl.librarypath` and explicitly locks `-Dorg.lwjgl.openal.libname=$nativeLibDir/libopenal.so`.
- **Fixed SDL3 Android JNI / `libpojavexec` initialization ordering (`org.lwjgl.sdl.SDL` & `CallbackBridge`):**
  Ensures `CallbackBridge.initializeSdl()` runs automatically in `org.lwjgl.sdl.SDL.<clinit>` before `Library.loadNative("SDL3")` regardless of which `SDL*` subsystem Minecraft touches first.
- **Fixed Android `SurfaceView` / `TextureView` native window lifecycle with SDL3 (`MinecraftGLSurface`):**
  Waits until `surfaceCreated` before calling `setupSDL` on `SurfaceView` (`Alternate Surface`) and updates `SDLSurface.setNativeSurface` across surface recreation even before `SDL_Init` completes.
- **Hardened `libltw_sdl_egl.so` for strict EGL 1.4 drivers (`ltw_sdl_egl.c` & `SdlSupport.getRendererEnv`):**
  Advertises `EGL_KHR_create_context` in `eglQueryString(EGL_EXTENSIONS)` so SDL3's `SDL_EGL_CreateContext` does not abort before context creation on strict EGL 1.4 drivers, adds fallback context attributes, and routes GLES translation renderers (`LTW`, `RustGL`, `GL4ES`, `Krypton`) through `libltw_sdl_egl.so`.
- **Skipped legacy Caciocavallo17 AWT injection on Java 21/25 (`LaunchArgs.kt`):**
  Avoids loading broken `sun.java2d.SurfaceManagerFactory` hooks and allocating duplicate AWT screen buffers on Java 21/25 where Minecraft 26.x runs headless/LWJGL.
- **Fixed `latestlog.txt` paste truncation & `SIGABRT` pipe loss (`utils.c`, `JREUtils.java`, `stdio_is.c`, `jre_launcher.c`, `ErrorActivity.kt`):**
  Eliminates 10 KB of pre-launch log spam (`RuntimeLoader: dlopen ... success` and the 5.8 KB `-cp` dump) that caused Android's clipboard to cut off logs at `JVMArg: -Dja`, drains `stdout`/`stderr` pipes before `nominal_exit`, zero-initializes `sigactions` in `abort_waiter_setup`, and displays the `jvm_crash_report.log` and `latestlog.txt` tail directly on the crash screen with selectable text.
- **Video Settings Graphics API switch (`1.0.0-alpha.4+`):**
  Switch between **OpenGL** and **Vulkan** directly in **Settings → Video → Graphics API**.

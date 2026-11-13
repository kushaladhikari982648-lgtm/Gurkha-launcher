## Gurkha Launcher 1.0.0-alpha.9

### Fixes in 1.0.0-alpha.9
- **Minecraft 26.3 SDL3 `GL.fixPojavGLContext` / `libglfw.so` Crash Fix (`GlDevice.java:114`)**:
  - In `lwjgl-opengl.jar` (LWJGL 3.4.1), `GL.createCapabilities()` checks `System.getenv("POJAV_RENDERER") != null` and calls `GL.fixPojavGLContext()`, which invokes `Class.forName("org.lwjgl.glfw.GLFW")`.
  - In SDL3 mode (`26.3+`), `JREUtils.setRendererEnv` no longer sets `POJAV_RENDERER`, so `GL.createCapabilities()` skips the GLFW-only `fixPojavGLContext()` reflection call entirely.
  - Added `-Dorg.lwjgl.glfw.libname=$nativeLibDir/libpojavexec.so` to `SdlSupport.getJvmArgs` and redirected `libglfw.so` -> `libpojavexec.so` in `lwjgl_dlopen_hook.c` (`ndlopen_bugfix`), plus made `GLFW.glfwMakeContextCurrent` a safe no-op when `POJAV_WINDOW_BACKEND=sdl`.
- Includes all fixes from `1.0.0-alpha.8` (Minecraft 26.2 `LibFFI` / `FreeType` `JNI_OnLoad` fix and RenderPearl multi-window GLFW/EGL surface transfer) and `1.0.0-alpha.7` (SDL3 OpenGL 3.3 attribute check fix, single-window Android SDL3 reuse, OpenGL fallback when Vulkan is unsupported, Video Settings Graphics API switch, and 32-bit ARM `-Xss1024k` stack fix).

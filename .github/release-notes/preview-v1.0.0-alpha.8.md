## Gurkha Launcher 1.0.0-alpha.8

### Fixes in 1.0.0-alpha.8
- **Minecraft 26.2 `LibFFI` / `FreeType` `ExceptionInInitializerError` Crash Fix**:
  - In LWJGL 3.4.1 (`26.1` / `26.2`), `org.lwjgl.system.Library.<clinit>` calls `System.loadLibrary("pojavexec")` (`Library.java:63`) **before** loading `liblwjgl.so` (`Library.java:67`).
  - Removed eager `org/lwjgl/glfw/GLFW` class lookup from `libpojavexec.so`'s `JNI_OnLoad` so loading `libpojavexec.so` during `Library.<clinit>` no longer triggers `GLFW.<clinit>` -> `GLFWErrorCallbackI.<clinit>` -> `LibFFI.<clinit>` before `liblwjgl.so` is loaded.
  - Added lazy, thread-safe `ensureGlfwRuntimeBridgeInitialized(JNIEnv*)` and `GLFW.nativeInitializeGLFWNativeBridge()` after `Library.initialize()` and inside `pojavInit()`.
- **Minecraft 26.2 RenderPearl Multi-Window GLFW/EGL Context & Surface Transfer**:
  - Updated `gl_bridge.c`, `osm_bridge.c`, and `GLFW.java` (`glfwDestroyWindow`) so RenderPearl's same-thread hidden utility window, temporary test window, and visible game window share the render thread's context and seamlessly transfer the `ANativeWindow` surface and `GLFW.mainContext`.
- Includes all fixes from `1.0.0-alpha.7` (SDL3 OpenGL 3.3 attribute check fix, single-window Android SDL3 reuse, OpenGL fallback when Vulkan is unsupported, Video Settings Graphics API switch, and 32-bit ARM `-Xss1024k` stack fix).

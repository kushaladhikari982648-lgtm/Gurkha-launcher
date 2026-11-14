# Gurkha Launcher 1.0.0

## Highlights
- **Phone Soft Keyboard in Chat Fixed Across All Versions (1.21.11, 26.1/26.2 GLFW, and 26.3+ SDL3)**:
  - **Minecraft 26.3+ (SDL3)**: Unified Android soft-keyboard text entry through `TouchCharInput` (`showKeyboard()` / `hideKeyboard()`), routed `SDL_StartTextInput` (`showTextInput`) and `COMMAND_TEXTEDIT_HIDE` directly to `TouchCharInput`, synchronized `SDLActivity.nativeFocusChanged` across `MainActivity` focus/resume/pause events, and updated `CallbackBridge.sendChar` / `sendKeycode` to ensure SDL keyboard focus and active text input (`nativeEnsureSdlKeyboardFocus`) before committing characters via `SDLInputConnection.nativeCommitText` (`SDL_EVENT_TEXT_INPUT`).
  - **Minecraft 26.1 / 26.2 (RenderPearl GLFW) & 1.21.11**: Updated `GLFW.glfwDestroyWindow` and `GLFW.glfwPollEvents` (`findPreferredShowingWindow()`) to prefer visible (`!win.state.hidden`) windows so hidden RenderPearl utility windows never steal `showingWindow` or drop chat/keyboard callbacks, and handled soft-keyboard newline (`\n`/`\r`) as Enter in `TouchCharInput`.
- **In-Game FPS Meter Fixed Across All Renderers & Window Backends**:
  - Moved FPS state (`currentFps`, `fpsFrameCount`, `lastFpsTimestampMs`, `hasExplicitFrameSwap`) into shared `pojav_environ` (`environ.h`) with monotonic millisecond timing (`pojavRecordFrame`) so `CallbackBridge.getCurrentFps()` reads live FPS across linker namespaces.
  - Added frame counting across GLFW OpenGL (`pojavSwapBuffers`), GLFW Vulkan (`pojavStopPumping`), SDL3 OpenGL (`SDL_GL_SwapWindow` / `eglSwapBuffers`), and SDL3 Vulkan (`SDL_PollEvent` frame boundary).
  - Enabled the floating FPS counter by default (`AllSettings.gameMenuShowFPS = true`) and fixed `GameMenuViewWrapper` initialization and elevation ordering.
- **Desktop-Style Launcher Front UI (Performance Card Removed)**:
  - Removed the front-screen performance setting card (`low_end_status` / `performance_button`) from both landscape and portrait home layouts (`fragment_launcher.xml`).
  - Styled the launcher home screen as a clean Liquid Glass desktop workspace with a dedicated navigation rail, top header bar, and right-hand instance/launch dock.

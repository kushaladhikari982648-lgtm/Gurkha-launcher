# Orbit Launcher — ver 1.0.0

## Highlights
- **Orbit Launcher Rebrand & New Logo**:
  - Updated launcher name and repository branding to **Orbit Launcher** (`kushaladhikari982648-lgtm/Orbit-Launcher`) while preserving package ID (`com.gurkha.launcher`) for seamless in-place upgrades.
  - Replaced adaptive, legacy, round, notification, and splash/header title banner icons with the new Orbit Launcher artwork.
- **Sleek Black Liquid-Glass Theme**:
  - Replaced all green theme accents, panels, buttons, headers, sliders, toggles, and outlines with a cohesive obsidian-black liquid-glass theme (`#050507` app background, `#0D0E12`–`#16181D` cards/panels, crisp `#F4F5F7` primary controls and typography).
- **Fixed Escape & All Custom/Keyboard/Mouse Buttons in Minecraft 26.3 and Other Versions**:
  - **Keycode Mapping (`EfficientAndroidLWJGLKeycode.java`)**: Prioritized PC keyboard mappings in `getAndroidKeycode(int)` so `GLFW_KEY_ESCAPE` maps to `KEYCODE_ESCAPE` (`111`) instead of `KEYCODE_BACK` (`4`) and `GLFW_KEY_HOME` maps to `KEYCODE_MOVE_HOME` (`122`) instead of `KEYCODE_HOME` (`3`); added missing GLFW keys (`DELETE`, `PRINT_SCREEN`, `SCROLL_LOCK`, `KP_EQUAL`, `LEFT_SUPER`, `RIGHT_SUPER`, `MENU`).
  - **SDL3 Single-Window Size & Focus (`sdl_hook.c` & `input_bridge_v3.c`)**: Created `sPrimaryWindow` at the full Android surface resolution (`savedWidth x savedHeight`) instead of the `320x480` RenderPearl hidden utility window size, stripped `SDL_WINDOW_HIDDEN` on `sPrimaryWindow`, hooked `SDL_SetWindowPosition` and `SDL_SetWindowRelativeMouseMode`, and synced `SDL_ShowWindow`, `SDL_OnApplicationDidEnterForeground`, `SDLActivity.onNativeResize`, `SDLActivity.nativeFocusChanged(true)`, and `SDLActivity.onNativeMouse` so keyboard and mouse focus are never lost in Minecraft 26.3+.
  - **GLFW Window Promotion & Back Button (`GLFW.java`, `Callbacks.java`, `MainActivity.java`)**: Promoted input-callback target windows to `showingWindow`, guarded `Callbacks.glfwFreeCallbacks` against non-primary utility windows, and wired Android Back navigation to dispatch `GLFW_KEY_ESCAPE` in-game.
- **Official Discord Community & Rich Presence**:
  - Discord Rich Presence (`1571705435713437726`) and official community server: **https://discord.gg/7FaUJf4R9**.

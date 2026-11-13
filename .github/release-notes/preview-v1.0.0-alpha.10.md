## Gurkha Launcher 1.0.0-alpha.10

### Minecraft 26.3+ SDL3 Landscape Orientation Fix
- **Prevented SDL3 from forcing portrait mode on launch (`SDLActivity.java`, `SdlSupport.kt`, `sdl_hook.c`)**:
  - In Minecraft 26.3+, `GlDevice.<init>` creates a non-resizable `64x64` hidden utility window (`"Minecraft - RenderPearl OpenGL Hidden Utility Window"`, `w = 64, h = 64, flags = 10L`). Stock `SDLActivity.setOrientationBis` evaluated `w > h` (`64 > 64 == false`) and called `MainActivity.setRequestedOrientation(SCREEN_ORIENTATION_SENSOR_PORTRAIT)`, forcing the phone into portrait mode and keeping it locked there when the primary window was reused.
  - Updated `SDLActivity.setOrientationBis` to always enforce landscape orientation (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`), added `SDL_ORIENTATIONS="LandscapeLeft LandscapeRight"` to `SdlSupport.getRendererEnv()`, and updated `sdl_hook.c` so square hidden utility windows satisfy `w > h` and reused primary windows update to the game's landscape dimensions.

### Launcher UI Layout, Liquid Glass Polish & Orientation Toggle
- **Right-Side Version Selector & Launch Panel**: Removed the duplicate right-side quick-links card (`Versions` / `Mods` / `Tools`) and moved the profile/version selector (`@id/version`) and Launch button (`@id/play_button`) (`@id/play_buttons_layout`) to the right side of the home screen while keeping the rest of the layout intact.
- **Liquid Glass Visual Polish**: Styled cards, buttons, headers, stat pills, and primary actions with translucent frosted-glass gradients and specular glass outlines.
- **Portrait / Landscape Switch Button**: Added an orientation toggle button in both the top launcher header bar (`@id/orientation_button`) and the home navigation bar (`@id/orientation_toggle_button`) to switch the launcher between Portrait and Landscape modes at any time.

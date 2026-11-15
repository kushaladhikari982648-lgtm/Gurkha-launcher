# Gurkha Launcher 1.0.1

## Highlights
- **No-Scroll Launch Button & Compact Left Navigation Rail**:
  - Compacted the left-side navigation rail (`launcher_menu`) and all workspace options (`Home`, `Versions`, `Mods`, `Settings`, `Tools`, `Portrait/Landscape`) to `31dp` item height so all options fit on screen without scrolling.
  - Compacted the main desktop launch dock (`play_layout` and `play_buttons_layout`) and top header bar so the **Launch (`play_button`)** button is immediately visible on screen without scrolling in both landscape and portrait modes.
- **Landscape Mode by Default with Switchable Portrait Mode**:
  - Set `LauncherActivity` to open in **Landscape mode (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`)** by default while keeping **Portrait mode (`SCREEN_ORIENTATION_PORTRAIT`)** switchable at any time via the header and sidebar orientation buttons.
- **Full VulkanMod (`net.vulkanmod`) Compatibility Across All Versions**:
  - Added automatic VulkanMod detection (`SdlSupport.hasVulkanMod`) across profile and default `mods` directories so any Minecraft version (1.16.5–1.21.11 and 26.x) with VulkanMod automatically enables the LWJGL 3.4.1 Vulkan/VMA/Shaderc/SPVC classpath and Android ARM32/ARM64 native libraries (`liblwjgl_vma.so`, `libshaderc.so`, `libspirv-cross-c-shared.so`, `libvulkan.so`).
  - Updated `org.lwjgl.system.linux.DynamicLinkLoader` (`jre_lwjgl3glfw` and `jre_lwjgl3sdl`) to load `libpojavexec.so` immediately in `<clinit>` before any `ndlopen` call, and enhanced `lwjgl_dlopen_hook.c` (`ndlopen_bugfix`) to redirect mod-extracted desktop `libshaderc`, `liblwjgl_vma`, `libspirv-cross`, and `libvulkan.so` loads to Android native binaries.
  - Exported `pojavInitVulkanLoader`, `pojavVulkanSupported`, `pojavGetRequiredInstanceExtensions`, `pojavGetInstanceProcAddress`, `pojavGetPhysicalDevicePresentationSupport`, and `pojavCreateWindowSurface` in `egl_bridge.c`, and added `gl_release_window_surface_for_vulkan()` in `gl_bridge.c` so `ANativeWindow` is cleanly detached from EGL before Vulkan swapchain creation.
  - Updated `sdl_hook.c` so `SDL_LoadFunction` resolves `VULKAN_PTR` against the real `libvulkan.so` handle and single-window Android SDL3 replaces any prior OpenGL window when a Vulkan window is requested.
- **Discord Account Linking & Live Rich Presence**:
  - Added `DiscordPresenceManager` and `DiscordLinkDialog` (accessible from the Home Screen **Discord** button in both landscape and portrait) supporting Web Sign-In, Discord Gateway v10 WebSocket Rich Presence (`Playing Gurkha Launcher`), and Discord Webhook status sharing.

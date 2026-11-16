# Gurkha Launcher 1.0.1

## Community, Help, Bug Reports & Feature Requests
- **Official Discord Server**: [https://discord.gg/7FaUJf4R9](https://discord.gg/7FaUJf4R9)
  - Join our Discord if you need help, found any bugs to report, or want to ask us to add new features!

## Highlights
- **No-Scroll Launch & Compact Left Navigation Rail**:
  - Compacted the left-side navigation bar (`Home`, `Versions`, `Mods`, `Settings`, `Tools`) so all options fit without scrolling.
  - Compacted the main launch card so the **Launch** button (`play_button`) is immediately visible without scrolling in both landscape and portrait modes.
- **Default Landscape Mode with Switchable Portrait Mode**:
  - The launcher now opens in landscape mode by default while keeping the top-bar and home-card orientation switch buttons so you can switch between landscape and portrait anytime.
- **VulkanMod Compatibility Across All Versions & Native Launch Stability**:
  - Added automatic `VulkanMod` (`net.vulkanmod`) detection (`SdlSupport.hasVulkanMod`), native `ANativeWindow` EGL-surface release before Vulkan surface creation (`gl_release_window_surface_for_vulkan` + `pojavSetWindowHint(GLFW_CLIENT_API, GLFW_NO_API)`), full GLFW Vulkan surface & extension bridge (`pojavInitVulkanLoader`, `pojavVulkanSupported`, `pojavGetRequiredInstanceExtensions`, `pojavGetInstanceProcAddress`, `pojavGetPhysicalDevicePresentationSupport`, `pojavCreateWindowSurface`), early `DynamicLinkLoader` hook initialization, and locked Android-native `libvulkan.so`, `liblwjgl_vma.so`, and `libshaderc.so` resolution across all Minecraft versions.
  - Hardened SDL3 single-window Vulkan/OpenGL window replacement and real `libvulkan.so` handle resolution.
- **Discord Linking, Community Server & Live Rich Presence (Application ID `1571705435713437726`)**:
  - Added Liquid Glass Discord Linking UI (`dialog_discord_link.xml`, `DiscordPresenceManager`, `DiscordLinkDialog`) styled identically to the Gurkha Launcher UI.
  - Connects via Discord Application ID `1571705435713437726` using your Discord ID / Username or one-step Discord Account Sign-In (no manual token pasting needed) so others on Discord see **Playing Gurkha Launcher**, plus direct access to the official Discord community (`https://discord.gg/7FaUJf4R9`).

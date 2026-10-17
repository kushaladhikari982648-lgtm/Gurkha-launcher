package com.movtery.zalithlauncher.launch

import net.kdt.pojavlaunch.JMinecraftVersionList
import java.io.File

/**
 * Minecraft 26.2 added a Vulkan renderer, chosen with the "Graphics API" video setting (options.txt key
 * preferredGraphicsBackend: "default", "opengl" or "vulkan"). Since 26.4 Snapshot 1, "Default" means Vulkan, and a
 * crash at startup no longer moves the setting back to OpenGL, so a device whose Vulkan driver crashes would need
 * options.txt to be edited by hand. All renderers of the launcher (LTW, GL4ES, Zink, ...) are OpenGL renderers that
 * Vulkan bypasses, so here "Default" (or no setting yet) starts the game with OpenGL, through the client's
 * --graphicsBackend argument (it exists since 26.2). Choosing Vulkan in the video settings still gives Vulkan.
 */
object GraphicsBackend {
    private const val OPTION = "preferredGraphicsBackend"

    /** The game arguments to add for a version ([versionInfo]) that runs in [gameDir]. */
    @JvmStatic
    fun getGameArgs(versionInfo: JMinecraftVersionList.Version?, gameDir: File): List<String> {
        // The Vulkan renderer comes with LWJGL's Vulkan bindings
        val hasVulkanRenderer = versionInfo?.libraries?.any { it?.name?.startsWith("org.lwjgl:lwjgl-vulkan:") == true } == true
        if (!hasVulkanRenderer) return emptyList()
        return when (readOption(File(gameDir, "options.txt"))) {
            null, "default" -> listOf("--graphicsBackend", "opengl")
            else -> emptyList() // the player's own choice ("opengl" or "vulkan")
        }
    }

    /** The value of preferredGraphicsBackend in options.txt (without quotes, lower case), or null if not set. */
    @JvmStatic
    fun readOption(optionsFile: File): String? = runCatching {
        if (!optionsFile.isFile) return null
        optionsFile.useLines { lines ->
            lines.firstOrNull { it.startsWith("$OPTION:") }
                ?.substringAfter(':')?.trim()?.trim('"')?.lowercase()?.ifEmpty { null }
        }
    }.getOrNull()
}

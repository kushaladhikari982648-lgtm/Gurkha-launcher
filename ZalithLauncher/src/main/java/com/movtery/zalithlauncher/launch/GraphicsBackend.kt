package com.movtery.zalithlauncher.launch

import com.movtery.zalithlauncher.setting.AllSettings
import net.kdt.pojavlaunch.JMinecraftVersionList
import java.io.File

/**
 * Minecraft 26.2 added a Vulkan renderer, chosen with the "Graphics API" video setting (options.txt key
 * preferredGraphicsBackend: "default", "opengl" or "vulkan"). Since 26.4 Snapshot 1, "Default" means Vulkan, and a
 * crash at startup no longer moves the setting back to OpenGL, so a device whose Vulkan driver crashes would need
 * options.txt to be edited by hand. All renderers of the launcher (LTW, GL4ES, Zink, ...) are OpenGL renderers that
 * Vulkan bypasses, so the launcher Video settings provide an explicit Graphics API button ("opengl" / "vulkan",
 * defaulting to "opengl") that syncs options.txt and passes --graphicsBackend.
 */
object GraphicsBackend {
    const val OPTION = "preferredGraphicsBackend"
    const val OPENGL = "opengl"
    const val VULKAN = "vulkan"

    @JvmStatic
    fun normalize(value: String?): String =
        if (value?.trim()?.trim('"')?.lowercase() == VULKAN) VULKAN else OPENGL

    @JvmStatic
    fun toggle(value: String?): String =
        if (normalize(value) == VULKAN) OPENGL else VULKAN

    /** The game arguments to add for a version ([versionInfo]) that runs in [gameDir]. */
    @JvmStatic
    @JvmOverloads
    fun getGameArgs(
        versionInfo: JMinecraftVersionList.Version?,
        gameDir: File,
        selectedBackend: String? = AllSettings.graphicsApi.getValue()
    ): List<String> {
        // The Vulkan renderer comes with LWJGL's Vulkan bindings
        val hasVulkanRenderer = versionInfo?.libraries?.any { it?.name?.startsWith("org.lwjgl:lwjgl-vulkan:") == true } == true
        if (!hasVulkanRenderer) return emptyList()
        val backend = normalize(selectedBackend)
        writeOption(File(gameDir, "options.txt"), backend)
        return listOf("--graphicsBackend", backend)
    }

    /** Updates preferredGraphicsBackend in options.txt if the file exists. */
    @JvmStatic
    fun writeOption(optionsFile: File, backend: String) {
        runCatching {
            if (!optionsFile.isFile) return
            val normalized = normalize(backend)
            val lines = optionsFile.readLines().toMutableList()
            val index = lines.indexOfFirst { it.startsWith("$OPTION:") }
            val replacement = "$OPTION:\"$normalized\""
            if (index >= 0) {
                if (lines[index] == replacement) return
                lines[index] = replacement
            } else {
                lines.add(replacement)
            }
            optionsFile.writeText(lines.joinToString("\n") + "\n")
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

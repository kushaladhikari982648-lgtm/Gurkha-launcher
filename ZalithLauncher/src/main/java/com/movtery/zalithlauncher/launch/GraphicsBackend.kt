package com.movtery.zalithlauncher.launch

import android.content.pm.PackageManager
import android.os.Build
import com.movtery.zalithlauncher.context.ContextExecutor
import com.movtery.zalithlauncher.feature.MCOptions
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
    const val STARTED_CLEANLY_OPTION = "startedCleanly"
    const val OPENGL = "opengl"
    const val VULKAN = "vulkan"

    @JvmStatic
    fun normalize(value: String?): String =
        if (value?.trim()?.trim('"')?.lowercase() == VULKAN) VULKAN else OPENGL

    @JvmStatic
    fun toggle(value: String?): String =
        if (normalize(value) == VULKAN) OPENGL else VULKAN

    @JvmStatic
    fun isVulkanHardwareSupported(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val pm = ContextExecutor.getApplication().packageManager
        pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
    }.getOrDefault(true)

    @JvmStatic
    @JvmOverloads
    fun resolveBackend(selectedBackend: String? = AllSettings.graphicsApi.getValue()): String {
        val requested = normalize(selectedBackend)
        return if (requested == VULKAN && !isVulkanHardwareSupported()) OPENGL else requested
    }

    /** The game arguments to add for a version ([versionInfo]) that runs in [gameDir]. */
    @JvmStatic
    @JvmOverloads
    fun getGameArgs(
        versionInfo: JMinecraftVersionList.Version?,
        gameDir: File,
        selectedBackend: String? = AllSettings.graphicsApi.getValue()
    ): List<String> {
        val backend = resolveBackend(selectedBackend)
        writeOption(File(gameDir, "options.txt"), backend)
        // The Vulkan renderer comes with LWJGL's Vulkan bindings
        val hasVulkanRenderer = versionInfo?.libraries?.any { it?.name?.startsWith("org.lwjgl:lwjgl-vulkan:") == true } == true
        if (!hasVulkanRenderer) return emptyList()
        return listOf("--graphicsBackend", backend)
    }

    /** Updates preferredGraphicsBackend and startedCleanly in options.txt (creating the file if missing) and syncs MCOptions. */
    @JvmStatic
    fun writeOption(optionsFile: File, backend: String) {
        val normalized = normalize(backend)
        runCatching {
            if (MCOptions.isInitialized()) {
                MCOptions.set(OPTION, "\"$normalized\"")
                MCOptions.set(STARTED_CLEANLY_OPTION, "true")
            }
        }
        runCatching {
            optionsFile.parentFile?.mkdirs()
            val lines = if (optionsFile.isFile) {
                optionsFile.readLines().toMutableList()
            } else {
                mutableListOf()
            }
            var changed = !optionsFile.isFile
            val backendLine = "$OPTION:\"$normalized\""
            val backendIndex = lines.indexOfFirst { it.startsWith("$OPTION:") }
            if (backendIndex >= 0) {
                if (lines[backendIndex] != backendLine) {
                    lines[backendIndex] = backendLine
                    changed = true
                }
            } else {
                lines.add(backendLine)
                changed = true
            }
            val cleanLine = "$STARTED_CLEANLY_OPTION:true"
            val cleanIndex = lines.indexOfFirst { it.startsWith("$STARTED_CLEANLY_OPTION:") }
            if (cleanIndex >= 0) {
                if (lines[cleanIndex] != cleanLine) {
                    lines[cleanIndex] = cleanLine
                    changed = true
                }
            } else {
                lines.add(cleanLine)
                changed = true
            }
            if (changed) {
                optionsFile.writeText(lines.joinToString("\n") + "\n")
            }
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

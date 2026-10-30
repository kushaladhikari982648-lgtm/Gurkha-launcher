package com.movtery.zalithlauncher.launch

import com.movtery.zalithlauncher.feature.version.Version
import com.movtery.zalithlauncher.utils.path.LibPath
import net.kdt.pojavlaunch.Architecture
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.multirt.Runtime
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar

/**
 * JVM options that lower the game's memory use with small RAM allocations (phones with 2-4 GB of RAM).
 *
 * Measured with the real Minecraft client in a singleplayer world (client and integrated server share the heap)
 * with a 500 MB heap and the launcher's default options (render distance 4), see the release notes of 1.0.0-beta.6:
 * - Compact object headers (Java 25, JEP 519, 8 instead of 12 bytes per object): about 10 % less heap in use
 *   (median heap after GC: 26.1.2 349 -> 315 MB, 26.2 333 -> 304 MB, 26.3 337 -> 303 MB) and fewer collections.
 *   Java 21 refuses the option ("Unrecognized VM option"), and it needs a 64-bit JVM.
 * - String deduplication (with every collector since Java 18): another few percent, fewer collections.
 * - Not used: Serial GC was worse (full collections triggered by class metadata, pauses up to 450 ms).
 *
 * Both are left out if the user's own Java arguments mention them, so either can be turned off there
 * (for example -XX:-UseCompactObjectHeaders).
 */
object LowMemoryProfile {
    /** Allocations up to this size get the options: the default allocation of every phone with less than 6 GB. */
    const val MAX_ALLOCATION_MB = 1536

    @JvmStatic
    fun getJvmArgs(runtime: Runtime, allocationMb: Int, userArgs: List<String>): List<String> {
        if (allocationMb > MAX_ALLOCATION_MB) return emptyList()
        fun userSet(name: String) = userArgs.any { it.contains(name) }
        val args = ArrayList<String>()
        if (runtime.javaVersion >= 18 && !userSet("StringDeduplication")) {
            args.add("-XX:+UseStringDeduplication")
        }
        if (runtime.javaVersion >= 25 && is64Bit(runtime) && !userSet("CompactObjectHeaders")) {
            args.add("-XX:+UseCompactObjectHeaders")
        }
        return args
    }

    /** Allocations up to this size get the smaller spawn area of Minecraft 1.14 - 1.20.4 (lowmem-agent.jar). */
    const val SPAWN_AREA_MAX_ALLOCATION_MB = 1024
    /** 18w43a, the first 1.14 snapshot, brought the chunk system with the fixed spawn area. */
    private val SPAWN_AREA_FIRST: Date = GregorianCalendar(2018, Calendar.OCTOBER, 10).time
    /** 1.20.5 made the spawn area a game rule (spawnChunkRadius, default 2). */
    private val SPAWN_AREA_FIXED: Date = GregorianCalendar(2024, Calendar.APRIL, 23).time

    /**
     * The Java agent option that makes the spawn area of Minecraft 1.14 - 1.20.4 smaller (jre_lowmem_agent,
     * SpawnAreaAgent), or null. Those versions load a fixed area of chunks around the spawn whenever a world opens,
     * which needs more memory than the game itself: with a 500 MB heap, singleplayer 1.18.2 - 1.20.4 ran out of
     * memory while opening the world (the server alone needed about 400 MB for it), although they need about 330 MB
     * once the world is open. The agent uses the size Minecraft 1.20.5 chose as its default; spawn chunks stay loaded
     * in a 5 x 5 area instead of 21 x 21.
     */
    @JvmStatic
    fun getSpawnAreaAgentArg(version: Version, allocationMb: Int): String? {
        if (allocationMb > SPAWN_AREA_MAX_ALLOCATION_MB || !LibPath.LOWMEM_AGENT.isFile) return null
        val released = RendererPolicy.originalReleaseDate(version) ?: return null
        if (released.before(SPAWN_AREA_FIRST) || !released.before(SPAWN_AREA_FIXED)) return null
        return "-javaagent:${LibPath.LOWMEM_AGENT.absolutePath}=3"
    }

    private fun is64Bit(runtime: Runtime): Boolean {
        val arch = runtime.arch?.let { Architecture.archAsInt(it) } ?: Tools.DEVICE_ARCHITECTURE
        return arch == Architecture.ARCH_ARM64 || arch == Architecture.ARCH_X86_64
    }
}

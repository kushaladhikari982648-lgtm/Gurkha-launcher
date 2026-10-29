package com.movtery.zalithlauncher.utils.platform

import android.os.Build
import java.io.File

/**
 * The device's total memory, read from /proc/meminfo, so that it can be used before any Context exists (the
 * defaults of AllSettings are created when the class is first used).
 */
object DeviceMemory {
    /** Total RAM in MB, or 0 if it can't be read. Phones sold as "2 GB" report about 1800-1900 MB. */
    @JvmStatic
    val totalMb: Int by lazy {
        runCatching {
            File("/proc/meminfo").useLines { lines ->
                lines.first { it.startsWith("MemTotal:") }.split(Regex("\\s+"))[1].toLong() / 1024
            }.toInt()
        }.getOrDefault(0)
    }

    /** Context-free default detection; GPU capabilities remain the renderer's responsibility. */
    @JvmStatic
    val isLowEnd: Boolean by lazy {
        LowEndPolicy.defaultEnabled(totalMb, Build.HARDWARE, Build.BOARD,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "")
    }

    /**
     * Phones with less than 3 GB of RAM (sold as 2 GB or 3 GB, usually with an entry-level GPU): the launcher uses
     * lighter defaults there, see AllSettings.
     */
    @JvmStatic
    val isLowRam: Boolean get() = totalMb in 1 until 3072
}

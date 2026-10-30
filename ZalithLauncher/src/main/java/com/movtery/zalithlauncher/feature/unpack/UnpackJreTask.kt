package com.movtery.zalithlauncher.feature.unpack

import android.content.Context
import android.content.res.AssetManager
import com.movtery.zalithlauncher.feature.log.Logging
import net.kdt.pojavlaunch.Architecture
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.multirt.MultiRTUtils

class UnpackJreTask(val context: Context, val jre: Jre) : AbstractUnpackTask() {
    private lateinit var assetManager: AssetManager
    private lateinit var launcherRuntimeVersion: String
    private var isCheckFailed: Boolean = false
    private val binPackName = "bin-${Architecture.archAsString(Tools.DEVICE_ARCHITECTURE)}.tar.xz"

    init {
        runCatching {
            assetManager = context.assets
            launcherRuntimeVersion = Tools.read(assetManager.open(jre.jrePath + "/version"))
            //并不是每个运行库都提供了所有架构的版本（例如 Java 25 没有 32 位 x86 版本）
            //Not every runtime is built for every CPU (e.g. there is no 32-bit x86 build of Java 25).
            //Skip it instead of failing (and retrying) the unpack on every launch.
            if (assetManager.list(jre.jrePath)?.contains(binPackName) != true) {
                Logging.i("UnpackJREAuto", "${jre.jreName} is not available for this device ($binPackName is missing), skipping it")
                isCheckFailed = true
            }
        }.getOrElse {
            isCheckFailed = true
        }
    }

    fun isCheckFailed() = isCheckFailed

    override fun isNeedUnpack(): Boolean {
        if (isCheckFailed) return false

        runCatching {
            val installedRuntimeVersion = MultiRTUtils.readInternalRuntimeVersion(jre.jreName)
            return launcherRuntimeVersion != installedRuntimeVersion
        }.getOrElse { e ->
            Logging.e("CheckInternalRuntime", Tools.printToString(e))
            return false
        }
    }

    override fun run() {
        listener?.onTaskStart()
        runCatching {
            assetManager.open(jre.jrePath + "/universal.tar.xz").use { universal ->
                assetManager.open("${jre.jrePath}/$binPackName").use { platformBins ->
                    MultiRTUtils.installRuntimeNamedBinpack(
                        universal,
                        platformBins,
                        jre.jreName, launcherRuntimeVersion
                    )
                }
            }
            MultiRTUtils.postPrepare(jre.jreName)
        }.getOrElse { e -> Logging.e("UnpackJREAuto", "Internal JRE unpack failed", e) }
        listener?.onTaskEnd()
    }
}

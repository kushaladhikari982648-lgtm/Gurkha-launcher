package com.movtery.zalithlauncher.feature.unpack

import com.movtery.zalithlauncher.feature.update.UpdateUtils
import com.movtery.zalithlauncher.launch.SdlSupport
import android.content.Context
import android.content.res.AssetManager
import com.movtery.zalithlauncher.feature.log.Logging.i
import com.movtery.zalithlauncher.utils.path.PathManager
import net.kdt.pojavlaunch.Tools
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class UnpackComponentsTask(val context: Context, val component: Components) : AbstractUnpackTask() {
    private lateinit var am: AssetManager
    private lateinit var rootDir: String
    private lateinit var versionFile: File
    private lateinit var input: InputStream
    private var isCheckFailed: Boolean = false

    init {
        runCatching {
            am = context.assets
            rootDir = if (component.privateDirectory) PathManager.DIR_DATA else PathManager.DIR_GAME_HOME
            versionFile = File("$rootDir/${component.component}/version")
            input = am.open("components/${component.component}/version")
        }.getOrElse {
            isCheckFailed = true
        }
    }

    fun isCheckFailed() = isCheckFailed

    override fun isNeedUnpack(): Boolean {
        if (isCheckFailed) return false
        if (component == Components.LWJGL3_SDL && !File(SdlSupport.getNativesDir(), "liblwjgl.so").exists()) {
            requestEmptyParentDir(versionFile)
            return true
        }

        if (!versionFile.exists()) {
            requestEmptyParentDir(versionFile)
            i("Unpack Components", "${component.component}: Pack was installed manually, or does not exist...")
            return true
        } else {
            val fis = FileInputStream(versionFile)
            val release1 = Tools.read(input)
            val release2 = Tools.read(fis)
            if (release1 != release2) {
                requestEmptyParentDir(versionFile)
                return true
            } else {
                i("UnpackPrep", "${component.component}: Pack is up-to-date with the launcher, continuing...")
                return false
            }
        }
    }

    override fun run() {
        listener?.onTaskStart()
        val fileList = am.list("components/${component.component}")
        for (fileName in fileList!!) {
            Tools.copyAssetFile(context, "components/${component.component}/$fileName", "$rootDir/${component.component}", true)
        }
        if (component == Components.LWJGL3_SDL) unpackSdlNatives()
        listener?.onTaskEnd()
    }

    /**
     * The LWJGL 3.4.1 natives for this device (assets of lwjgl-3.4.1-natives-release.aar), into a private
     * directory: they have the same names as the LWJGL 3.3.x natives packaged with the app.
     */
    private fun unpackSdlNatives() {
        val abi = UpdateUtils.getArchModel() ?: return
        val assetDir = "components/${SdlSupport.NATIVES_COMPONENT}/$abi"
        val target = SdlSupport.getNativesDir()
        if (target.exists()) FileUtils.deleteDirectory(target)
        target.mkdirs()
        am.list(assetDir)?.forEach { name ->
            Tools.copyAssetFile(context, "$assetDir/$name", target.absolutePath, true)
        }
    }

    private fun requestEmptyParentDir(file: File) {
        file.parentFile!!.apply {
            if (exists() and isDirectory) {
                FileUtils.deleteDirectory(this)
            }
            mkdirs()
        }
    }
}
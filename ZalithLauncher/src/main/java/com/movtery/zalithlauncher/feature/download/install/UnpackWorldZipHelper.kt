package com.movtery.zalithlauncher.feature.download.install

import com.movtery.zalithlauncher.feature.log.Logging
import net.kdt.pojavlaunch.utils.ZipUtils
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

class UnpackWorldZipHelper {
    companion object {
        fun unpackFile(zipFile: File, targetPath: File) {
            val levelPath = extractLevelPath(zipFile)
                ?: throw IOException("level.dat was not found in ${zipFile.name}")
            Logging.i("UnpackWorldZipHelper", "Found the level of the level.data file: \"$levelPath\"")
            val fileName = zipFile.name.removeSuffix(".${zipFile.extension}")
            //只打开一次压缩包，并确保在解压完成后关闭它
            ZipFile(zipFile).use { zip ->
                ZipUtils.zipExtract(zip, levelPath, File(targetPath, fileName))
            }
            Logging.i("UnpackWorldZipHelper", "Decompression is complete")
            FileUtils.deleteQuietly(zipFile)
        }

        /**
         * 读取zip文件，并找到level.dat文件所在的路径
         * Find the folder inside the archive that contains the world's level.dat.
         * @param file 压缩包文件
         * @return "" if level.dat is at the root of the archive, otherwise the folder path ending with "/",
         *         or null if the archive does not contain a world
         */
        private fun extractLevelPath(file: File): String? {
            if (!file.exists() || !file.isFile) {
                return null
            }

            if (!file.name.endsWith(".zip", ignoreCase = true)) {
                return null
            }

            ZipFile(file).use { zip ->
                val levelDatEntry = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    //只匹配真正的 level.dat（不匹配 xxxlevel.dat）
                    .filter { it.name.substringAfterLast('/').equals("level.dat", ignoreCase = true) }
                    //最浅的 level.dat 才是存档根目录（避免匹配到存档内的备份）
                    .minByOrNull { entry -> entry.name.count { it == '/' } }
                    ?: return null
                val path = levelDatEntry.name
                val lastSlash = path.lastIndexOf('/')
                //level.dat 位于压缩包根目录时，返回空字符串（解压全部内容）
                return if (lastSlash < 0) "" else path.substring(0, lastSlash + 1)
            }
        }
    }
}

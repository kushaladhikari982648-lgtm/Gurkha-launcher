package com.movtery.zalithlauncher.feature.background

import android.content.Context
import android.net.Uri
import com.movtery.zalithlauncher.R
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID

/** Bounded, atomic imports. Provider names cannot escape the wallpaper folder or overwrite files. */
object BackgroundImport {
    fun copy(context: Context, uri: Uri, folder: File): File {
        check(folder.isDirectory || folder.mkdirs()) { "Cannot create background directory" }
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        } ?: uri.lastPathSegment ?: "wallpaper"
        var name = displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_").takeLast(100)
        if (!name.contains('.')) {
            context.contentResolver.getType(uri)?.let { mime ->
                MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name += ".$it" }
            }
        }
        val id = UUID.randomUUID().toString()
        val temp = File(folder, "$id.part")
        val target = File(folder, "$id-$name")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot read wallpaper" }
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 256L * 1024 * 1024) { context.getString(R.string.video_wallpaper_error) }
                        output.write(buffer, 0, count)
                    }
                    require(total > 0) { "Wallpaper file is empty" }
                }
            }
            check(temp.renameTo(target)) { "Cannot save wallpaper" }
            return target
        } finally { temp.delete() }
    }
}

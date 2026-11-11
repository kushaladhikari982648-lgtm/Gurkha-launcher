package com.movtery.zalithlauncher.ui.activity

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.databinding.ActivityWardrobeBinding
import com.movtery.zalithlauncher.feature.media.MediaPolicy
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local wardrobe. No account tokens or network uploads. In-game application uses CSL. */
class WardrobeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWardrobeBinding
    private val worker = Executors.newSingleThreadExecutor()
    private val directory by lazy { File(filesDir, "wardrobe").apply { mkdirs() } }
    private val prefs by lazy { getSharedPreferences("wardrobe", MODE_PRIVATE) }
    private var pendingPlayer = ""
    private val skinPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { importTexture(it, "skin") } }
    private val capePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { importTexture(it, "cape") } }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { work { export(it, pendingPlayer); getString(R.string.wardrobe_exported) } }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        binding = ActivityWardrobeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pendingPlayer = state?.getString("player") ?: ""
        binding.wardrobePlayer.setText(prefs.getString("player", ""))
        binding.wardrobeBack.setOnClickListener { finish() }
        binding.importSkin.setOnClickListener { skinPicker.launch(arrayOf("image/png")) }
        binding.importCape.setOnClickListener { capePicker.launch(arrayOf("image/png")) }
        binding.exportWardrobe.setOnClickListener {
            pendingPlayer = binding.wardrobePlayer.text.toString().trim()
            if (!MediaPolicy.validPlayerName(pendingPlayer)) {
                binding.wardrobePlayer.error = getString(R.string.wardrobe_invalid_name)
            } else if (selected("skin") == null && selected("cape") == null) {
                Toast.makeText(this, R.string.wardrobe_select_first, Toast.LENGTH_LONG).show()
            } else {
                prefs.edit().putString("player", pendingPlayer).apply()
                exporter.launch("Gurkha-wardrobe-$pendingPlayer.zip")
            }
        }
        refresh()
    }
    override fun onSaveInstanceState(out: Bundle) { out.putString("player", pendingPlayer); super.onSaveInstanceState(out) }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
    private fun selected(kind: String): File? = prefs.getString(kind, null)?.let { name ->
        File(directory, name).takeIf { it.isFile && it.canonicalFile.parentFile == directory.canonicalFile }
    }
    private fun work(task: () -> String) {
        binding.wardrobeBusy.visibility = View.VISIBLE
        binding.wardrobeItems.visibility = View.GONE
        binding.importSkin.isEnabled = false; binding.importCape.isEnabled = false; binding.exportWardrobe.isEnabled = false
        worker.execute {
            val result = runCatching(task)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.wardrobeBusy.visibility = View.GONE
                binding.wardrobeItems.visibility = View.VISIBLE
                binding.importSkin.isEnabled = true; binding.importCape.isEnabled = true; binding.exportWardrobe.isEnabled = true
                result.fold({ Toast.makeText(this, it, Toast.LENGTH_LONG).show(); refresh() }, {
                    AlertDialog.Builder(this).setTitle(R.string.generic_error).setMessage(it.message ?: it.javaClass.simpleName).setPositiveButton(android.R.string.ok, null).show()
                })
            }
        }
    }
    private fun importTexture(uri: Uri, kind: String) = work {
        require((directory.listFiles()?.count { it.extension == "png" } ?: 0) < 32) { getString(R.string.wardrobe_full) }
        val temp = File(directory, UUID.randomUUID().toString() + ".part")
        try {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                temp.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= 4 * 1024 * 1024) { getString(R.string.wardrobe_invalid_png) }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val header = ByteArray(8)
            temp.inputStream().use { require(it.read(header) == 8) }
            require(header.contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) { getString(R.string.wardrobe_invalid_png) }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.path, options)
            require(MediaPolicy.validTexture(options.outWidth, options.outHeight, kind == "cape")) { getString(R.string.wardrobe_invalid_png) }
            val decoded = BitmapFactory.decodeFile(temp.path)
            requireNotNull(decoded) { getString(R.string.wardrobe_invalid_png) }; decoded.recycle()
            val dest = File(directory, "$kind-${UUID.randomUUID()}.png")
            check(temp.renameTo(dest)) { "Cannot save texture" }
            prefs.edit().putString(kind, dest.name).commit()
            getString(R.string.file_added)
        } finally { temp.delete() }
    }
    private fun export(uri: Uri, player: String) {
        require(MediaPolicy.validPlayerName(player))
        val skin = selected("skin"); val cape = selected("cape")
        require(skin != null || cape != null)
        val profile = JSONObject().put("name", "GurkhaLocal").put("type", "Legacy").put("checkPNG", true).put("model", "auto")
        contentResolver.openOutputStream(uri, "w").use { stream ->
            ZipOutputStream(requireNotNull(stream)).use { zip ->
                fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                for ((type, file) in listOf("skin" to skin, "cape" to cape)) if (file != null) {
                    profile.put(type, "GurkhaLocal/${type}s/{USERNAME}.png")
                    entry("CustomSkinLoader/GurkhaLocal/${type}s/$player.png", file.readBytes())
                }
                entry("CustomSkinLoader/ExtraList/GurkhaLocal.json", profile.toString(2).toByteArray(Charsets.UTF_8))
                entry("README.txt", getString(R.string.wardrobe_export_help).toByteArray(Charsets.UTF_8))
            }
        }
    }
    private fun refresh() {
        binding.wardrobeItems.removeAllViews()
        directory.listFiles()?.filter { it.extension == "png" }?.sortedBy { it.name }?.forEach { file ->
            val kind = if (file.name.startsWith("skin-")) "skin" else "cape"
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 16, 0, 16) }
            val image = ImageView(this).apply { contentDescription = getString(if (kind == "skin") R.string.import_skin else R.string.import_cape); scaleType = ImageView.ScaleType.FIT_CENTER }
            row.addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (128 * resources.displayMetrics.density).toInt()))
            Glide.with(this).load(file).override(256).into(image)
            row.addView(Button(this).apply {
                text = getString(if (selected(kind) == file) R.string.wardrobe_selected else R.string.wardrobe_select)
                setOnClickListener {
                    if (selected(kind) == file) prefs.edit().remove(kind).apply()
                    else prefs.edit().putString(kind, file.name).apply()
                    refresh()
                }
            })
            row.addView(Button(this).apply {
                setText(R.string.generic_delete)
                setOnClickListener { AlertDialog.Builder(this@WardrobeActivity).setMessage(R.string.wardrobe_delete)
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                        if (file.delete() && prefs.getString(kind, null) == file.name) prefs.edit().remove(kind).apply()
                        refresh()
                    }.show() }
            })
            binding.wardrobeItems.addView(row)
        }
    }
}

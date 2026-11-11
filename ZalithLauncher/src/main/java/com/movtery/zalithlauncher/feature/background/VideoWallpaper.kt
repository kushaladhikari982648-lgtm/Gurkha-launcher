package com.movtery.zalithlauncher.feature.background

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import com.movtery.zalithlauncher.R
import java.io.File

/** Muted launcher-only video. Releases the decoder when the launcher loses focus. */
class VideoWallpaper(private val view: TextureView) : TextureView.SurfaceTextureListener {
    private var file: File? = null
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var resumed = false
    private var failed = false
    init { view.isOpaque = false; view.surfaceTextureListener = this }

    fun setFile(value: File?) {
        if (file == value) return
        releasePlayer()
        file = value
        failed = false
        view.visibility = if (value == null) View.GONE else View.VISIBLE
        startIfReady()
    }
    fun resume() { resumed = true; startIfReady() }
    fun pause() { resumed = false; releasePlayer() }
    fun close() { pause(); view.surfaceTextureListener = null }

    private fun startIfReady() {
        val source = file ?: return
        if (!resumed || failed || !view.isAvailable || player != null) return
        try {
            require(source.length() <= 256L * 1024 * 1024) { "Wallpaper exceeds 256 MB" }
            val p = MediaPlayer()
            player = p
            surface = Surface(view.surfaceTexture)
            p.setSurface(surface)
            p.setVolume(0f, 0f)
            p.isLooping = true
            p.setOnPreparedListener {
                if (player !== it || !resumed) return@setOnPreparedListener
                if (it.videoWidth <= 0 || it.videoHeight <= 0 || it.videoWidth.toLong() * it.videoHeight > 1920L * 1080) {
                    fail(); return@setOnPreparedListener
                }
                scale(it.videoWidth, it.videoHeight)
                it.start()
            }
            p.setOnErrorListener { _, _, _ -> fail(); true }
            p.setDataSource(source.absolutePath)
            p.prepareAsync()
        } catch (_: Exception) { fail() }
    }
    private fun fail() {
        failed = true
        releasePlayer()
        view.visibility = View.GONE
        Toast.makeText(view.context, R.string.video_wallpaper_error, Toast.LENGTH_LONG).show()
    }
    private fun scale(w: Int, h: Int) {
        if (w == 0 || h == 0 || view.width == 0 || view.height == 0) return
        val factor = maxOf(view.width.toFloat() / w, view.height.toFloat() / h)
        view.setTransform(Matrix().apply { setScale(w * factor / view.width, h * factor / view.height, view.width / 2f, view.height / 2f) })
    }
    private fun releasePlayer() {
        player?.setOnPreparedListener(null)
        player?.setOnErrorListener(null)
        player?.release(); player = null
        surface?.release(); surface = null
    }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) { startIfReady() }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) { player?.let { if (it.isPlaying) scale(it.videoWidth, it.videoHeight) } }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { releasePlayer(); return true }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
}

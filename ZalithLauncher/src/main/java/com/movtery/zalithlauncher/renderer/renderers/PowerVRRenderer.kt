package com.movtery.zalithlauncher.renderer.renderers

import com.movtery.zalithlauncher.renderer.RendererInterface

/**
 * PowerVR Unified: the single Krypton-based libpowervr.so from Powervr-the-poop,
 * with an LTW-derived indirect-multidraw port. NOT the dual-backend package.
 * Pinned source, build instructions and limitations are recorded in powervr/README.md.
 * Uses the existing GL4ES context bridge and system EGL, not LTW's EGL wrappers.
 */
class PowerVRRenderer : RendererInterface {
    override fun getRendererId(): String = ID
    override fun getUniqueIdentifier(): String = "b806ecb2-68e0-49e4-9583-bcd1b2cff58f"
    override fun getRendererName(): String = "PowerVR Unified (Experimental)"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        // Matches upstream's unified Zalith adapter. Explicitly set ES: the ID suffix
        // must not be interpreted as an EGL_CONTEXT_CLIENT_VERSION by JREUtils.
        // Do not spoof a higher GL version or hide errors to imply modern MC support.
        mapOf("LIBGL_ES" to "3")
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }
    override fun getRendererLibrary(): String = LIBRARY
    override fun getRendererEGL(): String = "libEGL.so"

    companion object {
        const val ID = "opengles3_powervr"
        const val LIBRARY = "libpowervr.so"
    }
}

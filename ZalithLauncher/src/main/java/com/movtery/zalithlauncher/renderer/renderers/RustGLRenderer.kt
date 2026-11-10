package com.movtery.zalithlauncher.renderer.renderers

import com.movtery.zalithlauncher.renderer.RendererInterface

/** Built from rustgl/. Original: whaltermc/rust-renderer; Android work: RustGL-for-Gurkha-launcher.
 * Experimental GLES compatibility layer, not a Vulkan driver or a guarantee of game compatibility.
 * The desktop GL version is capability-tiered by the renderer, never forced to 4.5 here.
 */
class RustGLRenderer : RendererInterface {
    override fun getRendererId() = ID
    override fun getUniqueIdentifier() = "9e2c7b41-58d3-4f6a-8c1e-0b7a3d5f9e24"
    override fun getRendererName() = "RustGL (experimental · GLES 3)"
    override fun getRendererLibrary() = LIBRARY
    override fun getRendererEGL() = LIBRARY
    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }
    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        // Keep checks, precision and user JVM options intact. Never select the Vulkan PoC.
        mapOf("LIBGL_ES" to "3", "RENDERER_BACKEND" to "gles")
    }
    companion object {
        const val ID = "opengles3_rust"
        const val LIBRARY = "librust_gl.so"
    }
}

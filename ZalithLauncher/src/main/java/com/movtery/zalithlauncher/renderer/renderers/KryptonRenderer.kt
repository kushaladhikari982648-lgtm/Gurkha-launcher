package com.movtery.zalithlauncher.renderer.renderers

import com.movtery.zalithlauncher.renderer.RendererInterface

/**
 * Krypton Wrapper (NG-GL4ES, https://github.com/BZLZHH/NG-GL4ES, MIT): a fork of GL4ES that offers OpenGL 3.1 on
 * top of OpenGL ES 3, translating shaders with glslang and SPIRV-Cross. It works with Sodium and many shader packs.
 * It runs on the launcher's GL4ES bridge with an OpenGL ES 3 context and is configured like its official plugin app
 * (https://github.com/BZLZHH/NGG-FCLRendererPlugin). The library (ZalithLauncher/libs/ng_gl4es-release.aar) is built
 * from the R0.4.5 source for every ABI, see ng_gl4es-release.txt.
 *
 * Minecraft 26.3+ (SDL3) can't use it (SDL creates the EGL context itself), the renderer policy switches those
 * versions to LTW.
 */
class KryptonRenderer : RendererInterface {
    override fun getRendererId(): String = ID

    override fun getUniqueIdentifier(): String = "6f1d7c2a-4b8e-4f0a-9d35-2e7c8b1a5f64"

    override fun getRendererName(): String = "Krypton Wrapper (OpenGL 3.1)"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        mapOf(
            "LIBGL_USE_MC_COLOR" to "1",
            "LIBGL_GL" to "31",
            "LIBGL_ES" to "3",
            "LIBGL_NORMALIZE" to "1",
            "LIBGL_NOERROR" to "1"
        )
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = LIBRARY

    companion object {
        /** "opengles..." makes the native code use the GL4ES bridge; "3" gives it an OpenGL ES 3 context. */
        const val ID = "opengles3"
        const val LIBRARY = "libng_gl4es.so"
    }
}

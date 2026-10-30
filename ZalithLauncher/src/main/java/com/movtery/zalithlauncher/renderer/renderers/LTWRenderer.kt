package com.movtery.zalithlauncher.renderer.renderers

import com.movtery.zalithlauncher.renderer.RendererInterface

/**
 * LTW ("Large Thin Wrapper"): desktop OpenGL 3.x/4.x on top of OpenGL ES 3, by the PojavLauncher and
 * MojoLauncher developers. The library is ZalithLauncher/libs/ltw-release.aar, built from
 * https://github.com/MojoLauncher/LTW (LGPL-3.0), see ZalithLauncher/libs/ltw-release.txt.
 *
 * Integrated like MojoLauncher does it:
 * - Minecraft 1.21.5 and newer need it (GL4ES can't run them), and so does Sodium on 1.17+;
 *   versions before 1.17 (OpenGL compatibility profile) can't use it. See [com.movtery.zalithlauncher.launch.RendererPolicy].
 * - Only offered when the device supports OpenGL ES 3 (see Renderers.getCompatibleRenderers).
 */
class LTWRenderer : RendererInterface {
    override fun getRendererId(): String = ID

    override fun getUniqueIdentifier(): String = "6f1c2a9e-3b7d-4e58-9a0c-5d2b8e7f4a13"

    override fun getRendererName(): String = "LTW"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        mapOf(
            // GLES 3 context. Must be set here: the launcher would otherwise derive "3_ltw" from the ID.
            "LIBGL_ES" to "3",
            // Same as MojoLauncher: keeps OptiFine and Minecraft's own error reporting from flooding the log
            "LIBGL_NOERROR" to "1",
            // Options of the Mesa GLSL compiler inside LTW, so that it accepts shaders written for desktop GL
            "force_glsl_extensions_warn" to "true",
            "allow_higher_compat_version" to "true",
            "allow_glsl_extension_directive_midshader" to "true"
        )
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = LIBRARY

    /**
     * LTW wraps eglCreateContext/eglMakeCurrent/eglDestroyContext and aborts on contexts it did not create,
     * so the launcher's EGL bridge must take its EGL entry points from libltw.so. Its eglGetProcAddress
     * returns those three and forwards every other EGL function to the system EGL.
     */
    override fun getRendererEGL(): String = LIBRARY

    companion object {
        const val ID = "opengles3_ltw"
        const val LIBRARY = "libltw.so"
    }
}

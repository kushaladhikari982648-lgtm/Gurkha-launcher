package com.movtery.zalithlauncher.renderer.renderers

import com.movtery.zalithlauncher.renderer.RendererInterface

/**
 * RustGL (https://github.com/whaltermc/rust-renderer, vendored with fixes under rustgl/): a
 * desktop OpenGL 4.5 compatibility surface written in Rust, running on top of the device's
 * OpenGL ES 3 driver (like GL4ES runs on OpenGL ES 2). It rewrites desktop GLSL to GLSL ES and
 * shims the desktop-only entry points, so it can run every Minecraft version from the
 * fixed-function era to the newest core-profile releases.
 *
 * The library (ZalithLauncher/libs/rustgl-release.aar) is built from the vendored source in
 * rustgl/ by GitHub Actions, see rustgl/README.md and rustgl-release.txt. It is integrated like
 * the official RustGL plugin for ZalithLauncher 2 would be, but the library ships inside this
 * launcher: RustGL initializes from here, no plugin install needed.
 *
 * Tuned for low-end devices (IMG PowerVR GE8300-class GPUs on 2-3 GB phones): the vendor EGL
 * surface is used through the launcher's EGL bridge (librust_gl.so re-exports it), a GLES 3
 * context is created, and the shim itself falls back to emulated paths (CPU-side base-vertex
 * draws, 32-bit driver probing) where those drivers are incomplete.
 */
class RustGLRenderer : RendererInterface {
    override fun getRendererId(): String = ID

    override fun getUniqueIdentifier(): String = "9e2c7b41-58d3-4f6a-8c1e-0b7a3d5f9e24"

    override fun getRendererName(): String = "RustGL"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        mapOf(
            // GLES 3 context. Must be set here: the launcher would otherwise derive "3_rust"
            // from the ID and pass that as EGL_CONTEXT_CLIENT_VERSION.
            "LIBGL_ES" to "3",
            // The GLES passthrough backend is the only one that draws Minecraft frames
            // (vulkan is a PoC in the renderer and auto-falls back to GLES).
            "RENDERER_BACKEND" to "gles",
            // Report OpenGL 4.5 core to the game and mods, as the upstream plugin does
            "RENDERER_SPOOF_GL" to "1",
            // LWJGL pointer checks off: needed for the null fog buffer path on 1.16 and older
            "JAVA_TOOL_OPTIONS" to "-Dorg.lwjgl.util.NoChecks=true"
        )
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = LIBRARY

    /** librust_gl.so re-exports the EGL entry points the launcher's egl_loader needs, like libltw.so. */
    override fun getRendererEGL(): String = LIBRARY

    companion object {
        /** "opengles..." uses the launcher's GL bridge; the "3" gives it an OpenGL ES 3 context. */
        const val ID = "opengles3_rust"
        const val LIBRARY = "librust_gl.so"
    }
}

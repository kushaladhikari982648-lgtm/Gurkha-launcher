package com.movtery.zalithlauncher.launch

import com.movtery.zalithlauncher.renderer.RendererInterface
import com.movtery.zalithlauncher.renderer.renderers.LTWRenderer
import com.movtery.zalithlauncher.utils.path.PathManager
import net.kdt.pojavlaunch.JMinecraftVersionList
import java.io.File

/**
 * Minecraft 26.3 and newer use SDL3 (through LWJGL 3.4.1) instead of GLFW for the window, the GL
 * context and input. For those versions the launcher:
 * - puts the official LWJGL 3.4.1 jars of the lwjgl3-sdl component on the classpath instead of its LWJGL
 *   3.3.6 + GLFW stub, preceded by lwjgl-sdl-patches.jar (module jre_lwjgl3sdl);
 * - loads the LWJGL 3.4.1 natives from a private directory (they would clash with the 3.3.x ones);
 * - lets SDL create its window on the game surface (org.libsdl.app, MinecraftGLSurface, CallbackBridge).
 * Ported from Amethyst-Android (https://github.com/AngelAuraMC/Amethyst-Android), whose LWJGL 3.4.1 and
 * SDL builds are used.
 */
object SdlSupport {
    const val COMPONENT = "lwjgl3-sdl"
    const val NATIVES_COMPONENT = "lwjgl-3.4.1-natives"
    private const val PATCHES_JAR = "lwjgl-sdl-patches.jar"
    private const val EGL_FRONT_FOR_LTW = "libltw_sdl_egl.so"

    /** Whether the version uses SDL3 instead of GLFW (its libraries include LWJGL's SDL module). */
    @JvmStatic
    fun usesSdl(versionInfo: JMinecraftVersionList.Version?): Boolean =
        versionInfo?.libraries?.any { it?.name?.startsWith("org.lwjgl:lwjgl-sdl:") == true } == true

    @JvmStatic
    fun getComponentDir(): File = File(PathManager.DIR_GAME_HOME, COMPONENT)

    @JvmStatic
    fun getNativesDir(): File = File(PathManager.DIR_DATA, NATIVES_COMPONENT)

    /** The LWJGL 3.4.1 classpath; the patched classes must come before the official jars. */
    @JvmStatic
    fun getClassPath(): String {
        val jars = getComponentDir().listFiles { file -> file.name.endsWith(".jar") }
            ?.sortedWith(compareBy({ it.name != PATCHES_JAR }, { it.name }))
            ?: emptyList()
        check(jars.any { it.name == PATCHES_JAR } && jars.any { it.name == "lwjgl-sdl.jar" }) {
            "The $COMPONENT component is incomplete, restart the launcher to unpack it again"
        }
        return jars.joinToString(":") { it.absolutePath }
    }

    /** JVM arguments pointing LWJGL 3.4.1 at its own natives. */
    @JvmStatic
    fun getJvmArgs(nativeLibDir: String): List<String> {
        val natives = getNativesDir().absolutePath
        return listOf(
            "-Dorg.lwjgl.librarypath=$natives",
            "-Dorg.lwjgl.freetype.libname=$natives/libfreetype.so",
            "-Dorg.lwjgl.shaderc.libname=$natives/libshaderc.so",
            "-Dorg.lwjgl.spvc.libname=$nativeLibDir/libspirv-cross-c-shared.so",
            "-Dorg.lwjgl.sdl.libname=$nativeLibDir/libSDL3.so",
            // jemalloc is not shipped
            "-Dorg.lwjgl.system.allocator=system"
        )
    }

    /**
     * Environment for SDL's EGL code: the renderer's GL library and, for LTW, the EGL front that lets SDL
     * create LTW contexts (see ltw_sdl_egl.c).
     */
    @JvmStatic
    fun getRendererEnv(renderer: RendererInterface, nativeLibDir: String): Map<String, String> {
        val env = mutableMapOf("SDL_OPENGL_LIBRARY" to "$nativeLibDir/${renderer.getRendererLibrary()}")
        if (renderer.getRendererId() == LTWRenderer.ID) {
            env["SDL_EGL_LIBRARY"] = "$nativeLibDir/$EGL_FRONT_FOR_LTW"
            env["LTW_SDL_EGL_LTW_LIBRARY"] = "$nativeLibDir/${LTWRenderer.LIBRARY}"
        } else {
            renderer.getRendererEGL()?.let { env["SDL_EGL_LIBRARY"] = "$nativeLibDir/$it" }
        }
        return env
    }
}

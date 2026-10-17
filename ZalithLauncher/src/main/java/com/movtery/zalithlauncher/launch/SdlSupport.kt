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
    const val GLFW_STUB_JAR = "lwjgl-glfw-stub.jar"
    private const val EGL_FRONT_FOR_LTW = "libltw_sdl_egl.so"

    /** Whether the version uses SDL3 instead of GLFW (its libraries include LWJGL's SDL module). */
    @JvmStatic
    fun usesSdl(versionInfo: JMinecraftVersionList.Version?): Boolean = hasLibrary(versionInfo, "org.lwjgl:lwjgl-sdl:")

    /**
     * Whether the version still uses GLFW but is built against LWJGL 3.4 (Minecraft 26.1, 26.2 and the 26.3
     * snapshots before SDL3). 26.2 added a Vulkan renderer, and with it LWJGL's Vulkan, VMA, SPIRV-Cross and
     * shaderc bindings, referenced from 165 classes of the game (its native library bootstrap included): the
     * launcher's LWJGL 3.3.6 set doesn't have them, so 26.2 failed with NoClassDefFoundError. 26.1 calls a
     * TinyFileDialogs method whose signature changed in 3.4. These versions get the launcher's GLFW stub with the
     * official LWJGL 3.4.1 jars and natives of the lwjgl3-sdl component, which resolve all their LWJGL references.
     */
    @JvmStatic
    fun usesLwjgl341WithGlfw(versionInfo: JMinecraftVersionList.Version?): Boolean {
        if (usesSdl(versionInfo)) return false
        val version = versionInfo?.libraries?.firstOrNull { it?.name?.startsWith("org.lwjgl:lwjgl:") == true }
            ?.name?.split(':')?.getOrNull(2) ?: return false
        val numbers = version.split('.', '-').mapNotNull { it.toIntOrNull() }
        val major = numbers.getOrElse(0) { 0 }
        val minor = numbers.getOrElse(1) { 0 }
        return major > 3 || (major == 3 && minor >= 4)
    }

    /** Set when a GLFW version on LWJGL 3.4.1 is started (see [usesLwjgl341WithGlfw]). */
    @JvmField
    @Volatile
    var lwjgl341Glfw = false

    private fun hasLibrary(versionInfo: JMinecraftVersionList.Version?, prefix: String): Boolean =
        versionInfo?.libraries?.any { it?.name?.startsWith(prefix) == true } == true

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

    /**
     * The classpath of GLFW versions on LWJGL 3.4.1: the launcher's GLFW stub (lwjgl-glfw-stub.jar of the lwjgl3
     * component, built by module jre_lwjgl3glfw) before the official LWJGL 3.4.1 jars, so that its GLFW classes
     * replace LWJGL's; without the SDL module and the SDL patches.
     */
    @JvmStatic
    fun getGlfwClassPath(): String {
        val stub = File(File(PathManager.DIR_GAME_HOME, "lwjgl3"), GLFW_STUB_JAR)
        check(stub.isFile) { "${stub.name} is missing, restart the launcher to unpack the LWJGL components again" }
        val jars = getComponentDir().listFiles { file ->
            file.name.endsWith(".jar") && file.name != PATCHES_JAR && file.name != "lwjgl-sdl.jar"
        }?.sortedBy { it.name } ?: emptyList()
        check(jars.any { it.name == "lwjgl.jar" }) {
            "The $COMPONENT component is incomplete, restart the launcher to unpack it again"
        }
        return (listOf(stub) + jars).joinToString(":") { it.absolutePath }
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

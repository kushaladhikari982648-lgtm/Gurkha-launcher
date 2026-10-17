import java.security.MessageDigest

plugins {
    java
}

/*
 * Minecraft 26.3 and newer use SDL3 (LWJGL 3.4.1) instead of GLFW. For those versions the launcher puts the
 * official LWJGL 3.4.1 jars of the lwjgl3-sdl component on the classpath, preceded by this small jar with
 * the few classes that differ (see src/). The jar and a version file (hash of all jars, so that devices
 * re-extract the component when anything changes) are written into the component folder.
 *
 * The LWJGL 3.4.1 jars and natives are the builds used by Amethyst
 * (https://github.com/AngelAuraMC/Amethyst-Android), and SDLInit/SDLMouse are its patched versions.
 */
val componentDir = rootProject.file("ZalithLauncher/src/main/assets/components/lwjgl3-sdl")
val patchesJarName = "lwjgl-sdl-patches.jar"

dependencies {
    compileOnly(fileTree(componentDir) {
        include("*.jar")
        exclude(patchesJarName)
    })
    compileOnly("org.jspecify:jspecify:1.0.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveFileName.set(patchesJarName)
    destinationDirectory.set(componentDir)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    val versionFile = File(componentDir, "version")
    outputs.file(versionFile)
    doLast {
        val digest = MessageDigest.getInstance("SHA-1")
        componentDir.listFiles { file -> file.name.endsWith(".jar") }!!
            .sortedBy { it.name }
            .forEach { digest.update(it.readBytes()) }
        versionFile.writeText(digest.digest().joinToString("") { "%02x".format(it) })
    }
}

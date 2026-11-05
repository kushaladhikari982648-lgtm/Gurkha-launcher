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
val androidJniJarName = "lwjgl-android-jni.jar"

// The Java-25 multi-release layer switches JNI to FFM. Android/ARM32 must keep the
// JNI ABI implemented by the bundled natives, rather than require a desktop FFM linker.
// Keep Java 11/17 layers, licenses and the original upstream jar; generate a separate core.
val androidJniCore by tasks.registering(Jar::class) {
    archiveFileName.set(androidJniJarName)
    destinationDirectory.set(componentDir)
    from(zipTree(File(componentDir, "lwjgl.jar"))) {
        exclude("META-INF/MANIFEST.MF", "META-INF/versions/25/**")
    }
    manifest { attributes("Multi-Release" to "true", "Automatic-Module-Name" to "org.lwjgl") }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
val nativesAar = rootProject.file("ZalithLauncher/libs/lwjgl-3.4.1-natives-release.aar")

dependencies {
    compileOnly(fileTree(componentDir) {
        include("*.jar")
        exclude(patchesJarName, androidJniJarName)
    })
    compileOnly("org.jspecify:jspecify:1.0.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}

tasks.jar {
    dependsOn(androidJniCore)
    inputs.file(nativesAar)
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
        // Native updates must invalidate the unpacked component too, even when jars are unchanged.
        nativesAar.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        versionFile.writeText(digest.digest().joinToString("") { "%02x".format(it) })
    }
}

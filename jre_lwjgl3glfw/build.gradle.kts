plugins {
    java
}

group = "org.lwjgl.glfw"

configurations.getByName("default").isCanBeResolved = true

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("lwjgl-glfw-classes")
    destinationDirectory.set(file("../ZalithLauncher/src/main/assets/components/lwjgl3/"))
    // Auto update the version with a timestamp so the project jar gets updated by Pojav
    doLast {
        val versionFile = file("../ZalithLauncher/src/main/assets/components/lwjgl3/version")
        versionFile.writeText(System.currentTimeMillis().toString())
    }
    from({
        configurations.getByName("default").map {
            println(it.name)
            if (it.isDirectory) it else zipTree(it)
        }
    })
    exclude("net/java/openjdk/cacio/ctc/**")
    manifest {
        attributes("Manifest-Version" to "3.3.6")
        attributes("Automatic-Module-Name" to "org.lwjgl")
    }
}

/*
 * The launcher's own classes alone (GLFW stub, CallbackBridge, ...), without the merged LWJGL 3.3.6 jars. Minecraft
 * versions that still use GLFW but need LWJGL 3.4.1 (26.2 brought Vulkan, VMA, SPIRV-Cross and shaderc bindings,
 * which the 3.3.6 set doesn't have) get this jar followed by the official LWJGL 3.4.1 jars of the lwjgl3-sdl
 * component (see SdlSupport.getGlfwClassPath in the launcher). Lands next to lwjgl-glfw-classes.jar and is
 * extracted with it, but the 3.3.6 classpath leaves it out.
 */
val stubJar by tasks.registering(Jar::class) {
    archiveFileName.set("lwjgl-glfw-stub.jar")
    destinationDirectory.set(file("../ZalithLauncher/src/main/assets/components/lwjgl3/"))
    from(sourceSets.main.get().output)
    exclude("net/java/openjdk/cacio/ctc/**")
    // Only for LWJGL 2 era versions; it needs lwjglx's Mouse, which is not part of LWJGL 3.4.1
    exclude("org/lwjgl/input/InfdevMouse*")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.jar {
    finalizedBy(stubJar)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}

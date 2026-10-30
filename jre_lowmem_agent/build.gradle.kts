plugins {
    java
}

/*
 * Java agent that makes the spawn area of Minecraft 1.14 - 1.20.4 smaller for launches with little memory (see
 * src/main/java/com/gurkha/lowmem/SpawnAreaAgent.java). The jar goes into the launcher's "components" component,
 * which devices unpack again when its version file changes.
 */
val componentDir = rootProject.file("ZalithLauncher/src/main/assets/components/components")

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveFileName.set("lowmem-agent.jar")
    destinationDirectory.set(componentDir)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest {
        attributes("Premain-Class" to "com.gurkha.lowmem.SpawnAreaAgent")
    }
}

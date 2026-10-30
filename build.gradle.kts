buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.github.megatronking.stringfog:gradle-plugin:5.2.0")
        classpath("com.github.megatronking.stringfog:xor:5.0.0")
        classpath("com.android.tools.build:gradle:8.2.2")
        // xz compression of the component jars in the APK (ZalithLauncher/build.gradle.kts, packJarAsXz)
        classpath("org.tukaani:xz:1.9")
    }
}

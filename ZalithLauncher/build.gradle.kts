import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import com.android.build.gradle.tasks.MergeSourceSetFolders
import com.github.megatronking.stringfog.plugin.StringFogExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("stringfog")
}
apply(plugin = "stringfog")

val getCFApiKey = {
    // CI passes an unset secret as an empty string, which must count as "no key" too
    System.getenv("CURSEFORGE_API_KEY")?.trim()?.takeIf { it.isNotEmpty() } ?: run {
        val curseforgeKeyFile = File(rootDir, "curseforge_key.txt")
        if (curseforgeKeyFile.canRead() && curseforgeKeyFile.isFile && curseforgeKeyFile.readText().isNotBlank()) {
            curseforgeKeyFile.readText().trim()
        } else {
            // The app checks for this value and tells the user that CurseForge is unavailable (Modrinth still works)
            logger.warn("BUILD: You have no CurseForge key, the curseforge api will get disabled !")
            "DUMMY"
        }
    }
}

val getBuildType = {
    val buildType = System.getenv("ZL_BUILD_TYPE") ?: "DEBUG"
    logger.warn("BUILD: Build Type --> $buildType")
    buildType
}

// Java/Kotlin package and Android namespace of the code (R, BuildConfig). Kept as is: renaming it
// would touch every source file and changes nothing for users.
val nameId = "com.movtery.zalithlauncher"
val generatedZalithDir = file("$buildDir/generated/source/zalith/java")
val launcherAPPName = project.findProperty("launcher_app_name") as? String ?: error("The \"launcher_app_name\" property is not set in gradle.properties.")
val launcherName = project.findProperty("launcher_name") as? String ?: error("The \"launcher_name\" property is not set in gradle.properties.")
// Package name of the installed app (what Android uses to tell apps apart).
val launcherApplicationId = project.findProperty("launcher_application_id") as? String ?: error("The \"launcher_application_id\" property is not set in gradle.properties.")
val launcherVersionCode = (project.findProperty("launcher_version_code") as? String)?.toIntOrNull() ?: error("The \"launcher_version_code\" property is not set as an integer in gradle.properties.")
val launcherVersionName = project.findProperty("launcher_version_name") as? String ?: error("The \"launcher_version_name\" property is not set in gradle.properties.")

configurations {
    create("instrumentedClasspath") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
}

configure<StringFogExtension> {
    implementation = "com.github.megatronking.stringfog.xor.StringFogImpl"
    fogPackages = arrayOf(nameId)
    kg = com.github.megatronking.stringfog.plugin.kg.RandomKeyGenerator()
    mode = com.github.megatronking.stringfog.plugin.StringFogMode.bytes
}

android {
    namespace = nameId
    compileSdk = 34

    signingConfigs {
        create("releaseBuild") {
            // The release key is never stored in the repository. CI decodes it from the
            // GURKHA_KEYSTORE_BASE64 secret into the file named by GURKHA_KEYSTORE_FILE
            // (see .github/workflows/android.yml and RELEASING.md).
            val keystorePath = System.getenv("GURKHA_KEYSTORE_FILE")
            val pwd = System.getenv("GURKHA_KEYSTORE_PASSWORD")
            if (!keystorePath.isNullOrBlank()) storeFile = file(keystorePath)
            storePassword = pwd
            keyAlias = System.getenv("GURKHA_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "gurkha"
            keyPassword = System.getenv("GURKHA_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: pwd
        }
        create("customDebug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = launcherApplicationId
        minSdk = 26
        targetSdk = 34
        versionCode = launcherVersionCode
        versionName = launcherVersionName
        multiDexEnabled = true //important
        manifestPlaceholders["launcher_name"] = launcherAPPName
        // Only the languages the launcher is translated into (res/values-*): the libraries (Material, AppCompat,
        // ...) bring texts for ~80 more, which made resources.arsc twice as big for nothing
        resourceConfigurations += listOf("en", "ar", "de", "fr", "pt-rBR", "ru", "vi", "zh-rCN", "zh-rTW")
    }

    buildTypes {
        // Must be unique per installed app: two apps declaring the same provider authority cannot be
        // installed side by side, so derive it from the application id (not from the code package).
        val storageProviderId = "$launcherApplicationId.storage_provider"

        getByName("debug") {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("customDebug")
            resValue("string", "storageProviderAuthorities", "$storageProviderId.debug")
        }
        create("proguard") {
            initWith(getByName("debug"))
            isMinifyEnabled = true
            isShrinkResources = true
        }
        create("proguardNoDebug") {
            initWith(getByName("proguard"))
            isDebuggable = false
        }
        getByName("release") {
            // Don't set to true or java.awt will be a.a or something similar.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
            resValue("string", "storageProviderAuthorities", storageProviderId)
            signingConfig = signingConfigs.getByName("releaseBuild")
        }
    }

    sourceSets["main"].java.srcDirs(generatedZalithDir)

    androidComponents {
        onVariants { variant ->
            variant.outputs.forEach { output ->
                if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                    val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
                    afterEvaluate {
                        val task = tasks.named("merge${variantName}Assets").get() as MergeSourceSetFolders
                        task.doLast {
                            val arch = System.getProperty("arch", "all")
                            val assetsDir = task.outputDir.get().asFile
                            // Every bundled runtime (jre-8, jre-17, jre-21, jre-25, ...) must be stripped,
                            // otherwise per-architecture APKs ship binaries for the other CPUs too.
                            val jreList = File("$assetsDir/components").listFiles()
                                ?.filter { it.isDirectory && it.name.startsWith("jre-") }
                                ?.map { it.name }
                                ?: emptyList()
                            println("arch:$arch")
                            jreList.forEach { jreVersion ->
                                val runtimeDir = File("$assetsDir/components/$jreVersion")
                                println("runtimeDir:${runtimeDir.absolutePath}")
                                runtimeDir.listFiles()?.forEach {
                                    if (arch != "all" && it.name != "version" && !it.name.contains("universal") && it.name != "bin-${arch}.tar.xz") {
                                        println("delete:${it} : ${it.delete()}")
                                    }
                                }
                                // A runtime that has no build for this CPU (Java 25 has no 32-bit x86 build) is useless
                                // here: drop its shared part too instead of shipping it (UnpackJreTask skips it).
                                if (arch != "all" && !File(runtimeDir, "bin-${arch}.tar.xz").exists()) {
                                    println("delete (no bin-${arch}.tar.xz):${runtimeDir} : ${runtimeDir.deleteRecursively()}")
                                }
                            }
                            // LWJGL 3.4.1 natives (Minecraft 26.3+, from lwjgl-3.4.1-natives-release.aar): keep only this ABI
                            val abiName = mapOf("arm" to "armeabi-v7a", "arm64" to "arm64-v8a", "x86" to "x86", "x86_64" to "x86_64")[arch]
                            if (abiName != null) {
                                File("$assetsDir/components/lwjgl-3.4.1-natives").listFiles()?.forEach {
                                    if (it.isDirectory && it.name != abiName) println("delete:${it} : ${it.deleteRecursively()}")
                                }
                            }
                        }
                    }

                    (output.getFilter(ABI)?.identifier ?: "all").let { abi ->
                        val baseName = "$launcherName-${if (variant.buildType == "release") defaultConfig.versionName else "Debug-${defaultConfig.versionName}"}"
                        output.outputFileName = if (abi == "all") "$baseName.apk" else "$baseName-$abi.apk"
                    }
                }
            }
        }
    }

    splits {
        val arch = System.getProperty("arch", "all")
        if (arch != "all") {
            abi {
                isEnable = true
                reset()
                when (arch) {
                    "arm" -> include("armeabi-v7a")
                    "arm64" -> include("arm64-v8a")
                    "x86" -> include("x86")
                    "x86_64" -> include("x86_64")
                }
            }
        }
    }

    ndkVersion = "25.2.9519653"

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("**/libbytehook.so")
        }
    }

    buildFeatures {
        prefab = true
        buildConfig = true
        viewBinding = true
    }

    buildToolsVersion = "34.0.0"
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

fun generateJavaClass(sourceOutputDir: File, packageName: String, className: String, constantMap: Map<String, String>) {
    val outputDir = File(sourceOutputDir, packageName.replace(".", "/"))
    outputDir.mkdirs()
    val javaFile = File(outputDir, "$className.java")
    val constants = constantMap.entries.joinToString("\n") { (key, value) ->
        "\tpublic static final String $key = \"$value\";"
    }
    javaFile.writeText(
        """
        |/**
        | * Automatically generated file. DO NOT MODIFY
        | */
        |package $packageName;
        |
        |public class $className {
        |$constants
        |}
        """.trimMargin()
    )
    println("Generated Java file: ${javaFile.absolutePath}")
}

tasks.register("generateInfoDistributor") {
    doLast {
        val constantMap = mapOf(
            "CURSEFORGE_API_KEY" to getCFApiKey(),
            "LAUNCHER_NAME" to project.property("launcher_name").toString(),
            "APP_NAME" to project.property("launcher_app_name").toString(),
            "BUILD_TYPE" to getBuildType()
        )
        generateJavaClass(generatedZalithDir, "com.movtery.zalithlauncher", "InfoDistributor", constantMap)
    }
}

tasks.named("preBuild") {
    dependsOn("generateInfoDistributor")
    // The lwjgl3-sdl component needs lwjgl-sdl-patches.jar and its version file (module jre_lwjgl3sdl)
    dependsOn(":jre_lwjgl3sdl:jar")
    // GLFW versions on LWJGL 3.4.1 (Minecraft 26.2) need the GLFW stub without the 3.3.6 jars (module jre_lwjgl3glfw)
    dependsOn(":jre_lwjgl3glfw:stubJar")
}

dependencies {
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    implementation("commons-codec:commons-codec:1.17.1")
    // implementation("com.wu-man:android-bsf-api:3.1.3")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0-beta01")
    implementation("androidx.annotation:annotation:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.palette:palette-ktx:1.0.0")

    implementation("com.github.duanhong169:checkerboarddrawable:1.0.2")
    implementation("com.github.PojavLauncherTeam:portrait-sdp:ed33e89cbc")
    implementation("com.github.PojavLauncherTeam:portrait-ssp:6c02fd739b")
    implementation("com.github.Mathias-Boulay:ExtendedView:1.0.0")
    implementation("com.github.Mathias-Boulay:android_gamepad_remapper:2.0.3")
    implementation("com.github.Mathias-Boulay:virtual-joystick-android:1.14")
    implementation("com.github.skydoves:powerspinner:1.2.7")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.github.angcyo.DslTablayout:TabLayout:3.6.5")

    implementation("com.github.megatronking.stringfog:xor:5.0.0")

    implementation("top.fifthlight.touchcontroller:proxy-client-android:0.0.2")

    // implementation("com.intuit.sdp:sdp-android:1.0.5")
    // implementation("com.intuit.ssp:ssp-android:1.0.5")

    implementation("org.tukaani:xz:1.9")
    // Our version of exp4j can be built from source at
    // https://github.com/PojavLauncherTeam/exp4j
    implementation("net.sourceforge.htmlcleaner:htmlcleaner:2.6.1")
    implementation("com.bytedance:bytehook:1.0.10")

    // implementation("net.sourceforge.streamsupport:streamsupport-cfuture:1.7.0")

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.commonmark:commonmark:0.19.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.flexbox:flexbox:3.0.0")

    implementation("com.getkeepsafe.taptargetview:taptargetview:1.14.0")
    implementation("io.github.petterpx:floatingx:2.3.3")
    implementation("org.greenrobot:eventbus:3.3.1")
    implementation("com.moandjiezana.toml:toml4j:0.7.2") {
        exclude(group = "com.google.code.gson", module = "gson")
    }
}

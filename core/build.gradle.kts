import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Everything except the UI: model, MIDI, transports, bridge protocol, sync, persistence.
// No Compose, so the headless bridge (bridge/) stays small enough for a Raspberry Pi Zero 2 W.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinxSerialization)
}

// AppInfo.VERSION comes from l6desk.version in gradle.properties (one version for every package).
abstract class GenerateAppVersion : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = outputDir.get().file("io/github/l6desk/AppVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package io.github.l6desk\n\ninternal const val APP_VERSION = \"${version.get()}\"\n")
    }
}

val generateVersion = tasks.register<GenerateAppVersion>("generateAppVersion") {
    version.set(providers.gradleProperty("l6desk.version"))
    outputDir.set(layout.buildDirectory.dir("generated/version"))
}

kotlin {
    jvm()

    androidLibrary {
        namespace = "io.github.l6desk.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateVersion.flatMap { it.outputDir })
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // Java code both the desktop/Pi (jvm) and Android can run: the music player and its WAV
        // reading (java.io files, threads), without Java Sound or java.nio.file (Android 7 has neither).
        val jvmAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)
    }
}

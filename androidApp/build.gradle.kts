import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core) // WindowInsetsControllerCompat (immersive mode)
    implementation(libs.compose.foundation)
}

val appVersion = providers.gradleProperty("l6desk.version").get()

/** 0.9.0 -> 900, 1.2.3 -> 10203: increases with every release. */
fun versionCodeOf(v: String): Int = v.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }

// Release signing: keystore.properties (git-ignored, made by scripts/make-release-key.sh) points at a
// keystore kept outside the repository. Without it, assembleRelease builds an unsigned APK.
val keystoreFile = providers.gradleProperty("l6desk.keystoreProperties").map { file(it) }
    .orElse(rootProject.layout.projectDirectory.file("keystore.properties").asFile.let { provider { it } }).get()
val keystoreProps = keystoreFile.takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "io.github.l6desk"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.l6desk"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
    }
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        // Development builds install beside the release ("L6Desk debug", its own settings and
        // songs), so the gig app on the tablet is never uninstalled to try a new build.
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

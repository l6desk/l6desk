import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

val appVersion = providers.gradleProperty("l6desk.version").get()

compose.desktop {
    application {
        mainClass = "io.github.l6desk.MainKt"

        nativeDistributions {
            // DEB on Ubuntu (./gradlew :desktopApp:packageDeb); MSI must be built on Windows
            // (./gradlew :desktopApp:packageMsi). Both bundle a Java runtime: nothing else to install.
            targetFormats(TargetFormat.Deb, TargetFormat.Msi)
            // Display name (menu entry, Windows Start menu); the Linux package keeps a plain name.
            packageName = "L6Desk"
            packageVersion = appVersion
            description = "Remote control desk for the Zoom LiveTrak L6max"
            vendor = "L6Desk"
            copyright = "© 2026 L6Desk contributors"
            // java.desktop: javax.sound.midi (USB MIDI); jdk.crypto.ec: signing problem reports (ECDSA;
            // part of java.base from Java 22); the rest from :desktopApp:suggestRuntimeModules.
            modules("java.desktop", "java.instrument", "jdk.unsupported", "jdk.crypto.ec")

            linux {
                packageName = "l6desk"
                iconFile.set(project.file("icons/l6desk.png"))
                menuGroup = "AudioVideo;Audio;Midi"
                appCategory = "sound"
                debMaintainer = "noreply@l6desk.invalid"
                shortcut = true
            }
            windows {
                iconFile.set(project.file("icons/l6desk.ico"))
                menuGroup = "L6Desk"
                dirChooser = true
                shortcut = true
                // Keep this fixed so new MSIs upgrade the installed version in place.
                upgradeUuid = "7b0d6d0e-5a4f-4b7e-9c55-2f3a8d1c6e41"
            }
        }
    }
}

// A bridge with the simulator behind it (scripts/bridge-sim.sh): tests the tablet path without the L6max.
tasks.register<JavaExec>("runBridgeSim") {
    group = "application"
    description = "Runs a Wi-Fi bridge with the L6max simulator behind it"
    mainClass.set("io.github.l6desk.BridgeSimKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

// Icon generator (src/tools): renders art/l6desk-icon.svg to the desktop and Android raster icons.
val tools: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].compileClasspath
    runtimeClasspath += sourceSets["main"].runtimeClasspath
}
tasks.register<JavaExec>("generateIcons") {
    group = "build"
    description = "Renders art/l6desk-icon.svg to the raster icons (outputs are committed)"
    mainClass.set("io.github.l6desk.tools.IconGenKt")
    classpath = tools.runtimeClasspath
    args(rootDir.absolutePath)
}

// The checker's classpath, for scripts/check-report.sh (which runs it with plain java, so a
// rejected report is a clear "REJECTED" and exit code 1, not a failed Gradle build).
tasks.register("printToolsClasspath") {
    group = "help"
    description = "Prints the classpath of the tools (scripts/check-report.sh)"
    val classpath = tools.runtimeClasspath
    dependsOn(classpath)
    doLast { println(classpath.asPath) }
}

// Headless Wi-Fi bridge for a small Linux board (Raspberry Pi Zero 2 W): L6max on USB, tablet on
// Wi-Fi. Depends only on core/, so the single jar stays a few MB. See docs/bridge-setup.md.
plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(projects.core)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(17) // runs on Java 17 (Raspberry Pi OS bookworm) and 21 (trixie)
}

val mainClassName = "io.github.l6desk.bridge.MainKt"

// Run on this computer (e.g. against the L6max on USB):  ./gradlew :bridge:run --args="--no-led"
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the headless bridge on this computer"
    mainClass.set(mainClassName)
    classpath = sourceSets["main"].runtimeClasspath
}

// One self-contained jar: build/libs/l6desk-bridge.jar
val bridgeJar = tasks.register<Jar>("bridgeJar") {
    group = "build"
    description = "Builds the bridge as a single runnable jar"
    archiveFileName.set("l6desk-bridge.jar")
    manifest { attributes("Main-Class" to mainClassName) }
    from(sourceSets["main"].output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
}

// Everything to copy to the Pi: build/dist/l6desk-bridge/ (jar, service, config, setup script)
tasks.register<Sync>("bridgeDist") {
    group = "build"
    description = "Collects the jar and Pi deployment files into build/dist/l6desk-bridge"
    from(bridgeJar)
    from("deploy")
    into(layout.buildDirectory.dir("dist/l6desk-bridge"))
}

package io.github.l6desk.report

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

actual fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        for ((name, bytes) in entries) {
            z.putNextEntry(ZipEntry(name))
            z.write(bytes)
            z.closeEntry()
        }
    }
    return out.toByteArray()
}

actual fun platformDetails(): Map<String, String> {
    val os = System.getProperty("os.name").orEmpty()
    // "Ubuntu 24.04.1 LTS": the distribution matters more than the kernel version on Linux.
    val distribution = if (os == "Linux") {
        runCatching {
            File("/etc/os-release").readLines().firstOrNull { it.startsWith("PRETTY_NAME=") }
                ?.substringAfter('=')?.trim('"')
        }.getOrNull()
    } else {
        null
    }
    return listOfNotNull(
        "os" to os,
        "osVersion" to System.getProperty("os.version").orEmpty(),
        "arch" to System.getProperty("os.arch").orEmpty(),
        distribution?.let { "distribution" to it },
        System.getenv("XDG_CURRENT_DESKTOP")?.let { "desktop" to it },
        "java" to "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})",
    ).toMap()
}

package io.github.l6desk.report

import android.os.Build
import java.io.ByteArrayOutputStream
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

actual fun platformDetails(): Map<String, String> = mapOf(
    "os" to "Android",
    "osVersion" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
    "abi" to Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
)

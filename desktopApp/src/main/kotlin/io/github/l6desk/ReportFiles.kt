package io.github.l6desk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI

/** REPORT on the desktop: saves the report zip with the system's save dialog, and opens GitHub in the browser. */
class ReportFiles(private val parent: () -> Frame?) {
    private var lastDir: String? = System.getProperty("user.home")?.let { home ->
        File(home, "Downloads").takeIf { it.isDirectory }?.path ?: home
    }

    suspend fun save(fileName: String, bytes: ByteArray): String? {
        val target = withContext(Dispatchers.Main) {
            val dialog = FileDialog(parent(), "Save the problem report", FileDialog.SAVE).apply {
                directory = lastDir
                file = fileName
                isVisible = true // blocks until closed
            }
            val dir = dialog.directory ?: return@withContext null
            val name = dialog.file ?: return@withContext null
            lastDir = dir
            File(dir, if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip")
        } ?: return null
        withContext(Dispatchers.IO) { target.writeBytes(bytes) }
        return target.path
    }

    /** Opens [url] in the browser: Java's own way, else xdg-open (Linux). Throws if neither works. */
    fun openUrl(url: String) {
        val desktop = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
        if (desktop && runCatching { Desktop.getDesktop().browse(URI(url)) }.isSuccess) return
        check(System.getProperty("os.name").orEmpty() == "Linux") { "No way to open a browser" }
        ProcessBuilder("xdg-open", url).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    }
}

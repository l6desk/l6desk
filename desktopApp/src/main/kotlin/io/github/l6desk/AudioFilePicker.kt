package io.github.l6desk

import io.github.l6desk.player.UploadSource
import io.github.l6desk.player.isAudioFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FileInputStream

/**
 * Picks music files to upload to the bridge with the system's file dialog (GTK on Ubuntu, native
 * on Windows), opening where it was last used.
 */
class AudioFilePicker(private val parent: () -> Frame?) {
    private var lastDir: String? = System.getProperty("user.home")?.let { home -> File(home, "Music").takeIf { it.isDirectory }?.path ?: home }

    suspend fun pick(): List<UploadSource> {
        val files = withContext(Dispatchers.Main) {
            val dialog = FileDialog(parent(), "Upload songs to the bridge", FileDialog.LOAD).apply {
                isMultipleMode = true
                directory = lastDir
                setFilenameFilter { _, name -> isAudioFile(name) }
                // Windows ignores the filter above; a pattern in the name box works there instead.
                if (System.getProperty("os.name").orEmpty().startsWith("Windows")) file = "*.wav;*.mp3;*.m4a;*.aac;*.flac"
                isVisible = true // blocks until closed
            }
            dialog.directory?.let { lastDir = it }
            dialog.files.orEmpty().toList()
        }
        return files.filter { it.isFile && isAudioFile(it.name) }.map(::FileSource)
    }

    private class FileSource(private val file: File) : UploadSource {
        private var stream: FileInputStream? = null
        override val name: String = file.name
        override val size: Long = file.length()

        override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
            (stream ?: FileInputStream(file).also { stream = it }).read(buffer)
        }

        override fun close() {
            runCatching { stream?.close() }
        }
    }
}

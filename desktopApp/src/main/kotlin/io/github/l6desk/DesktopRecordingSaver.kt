package io.github.l6desk

import io.github.l6desk.recordings.RecordingSaver
import io.github.l6desk.recordings.SavedFile
import java.io.File

/**
 * Where recordings downloaded through the Pi go on the desktop: the same folder as from USB,
 * ~/Music/L6Desk/Recordings/<recording>. Each file is written beside its name and renamed once complete.
 */
class DesktopRecordingSaver(private val base: File = File(File(System.getProperty("user.home"), "Music"), "L6Desk/Recordings")) : RecordingSaver {
    override val location: String = base.path

    override fun create(folder: String, name: String, bytes: Long): SavedFile {
        val dir = File(base, folder).apply { mkdirs() }
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        val out = part.outputStream().buffered(1 shl 20)
        return object : SavedFile {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun finish() {
                out.close()
                check(part.length() == bytes) { "Couldn't save all of $name" }
                if (target.exists()) target.delete()
                check(part.renameTo(target)) { "Couldn't save $name" }
            }
            override fun abort() {
                runCatching { out.close() }
                part.delete()
            }
        }
    }

    override val canOpenFolder = true

    override fun openFolder(folder: String): Boolean {
        val dir = File(base, folder).takeIf { it.isDirectory } ?: return false
        return runCatching {
            Thread { runCatching { java.awt.Desktop.getDesktop().open(dir) } }.start()
            true
        }.getOrDefault(false)
    }
}

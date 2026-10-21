package io.github.l6desk.persistence

import java.io.File

/**
 * A [TextStore] in a directory. Writes go to a temporary file that is then renamed over the old
 * one, so a crash or power cut mid-write never leaves a half-written file.
 */
class FileTextStore(private val dir: File) : TextStore {
    override fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.readText()

    override fun write(name: String, text: String) {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(dir, name))) {
            File(dir, name).delete()
            tmp.renameTo(File(dir, name))
        }
    }
}

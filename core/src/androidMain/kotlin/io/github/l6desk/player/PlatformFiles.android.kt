package io.github.l6desk.player

import java.io.File
import java.io.InputStream

/** Android: plain java.io (no Windows sharing rules to work around; works on Android 7). */
internal actual object PlatformFiles {
    actual fun openForReading(file: File): InputStream = file.inputStream()

    actual fun moveReplacing(from: File, to: File): Boolean = from.renameTo(to) || (to.delete() && from.renameTo(to))

    actual fun deletePlainFileOrLink(file: File) {
        // A link's canonical path is where it points; deleting the File removes the link itself.
        val isLink = runCatching { file.canonicalFile != file.absoluteFile.parentFile?.canonicalFile?.let { File(it, file.name) } }.getOrDefault(true)
        if (isLink || file.isFile) file.delete()
    }
}

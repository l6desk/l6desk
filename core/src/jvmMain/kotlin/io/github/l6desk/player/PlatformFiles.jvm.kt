package io.github.l6desk.player

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

internal actual object PlatformFiles {
    actual fun openForReading(file: File): InputStream = Files.newInputStream(file.toPath())

    actual fun moveReplacing(from: File, to: File): Boolean = runCatching {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.recoverCatching {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }.isSuccess

    actual fun deletePlainFileOrLink(file: File) {
        val path = file.toPath()
        if (Files.isSymbolicLink(path) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) runCatching { Files.delete(path) }
    }
}

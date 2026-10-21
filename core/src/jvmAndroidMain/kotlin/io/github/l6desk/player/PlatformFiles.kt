package io.github.l6desk.player

import java.io.File
import java.io.InputStream

/**
 * The few file operations the player needs that differ between the desktop/Pi and Android (whose
 * oldest supported version, 7, has no java.nio.file).
 */
internal expect object PlatformFiles {
    /**
     * Opens [file] for reading so that it can still be renamed or deleted meanwhile (on Windows,
     * FILE_SHARE_DELETE through NIO), as on Linux: EDIT works on the song playing.
     */
    fun openForReading(file: File): InputStream

    /** Moves [from] over [to], replacing it (a plain rename can't on Windows). */
    fun moveReplacing(from: File, to: File): Boolean

    /** Deletes [file] if it's a plain file or a link (never following the link); not folders. */
    fun deletePlainFileOrLink(file: File)
}

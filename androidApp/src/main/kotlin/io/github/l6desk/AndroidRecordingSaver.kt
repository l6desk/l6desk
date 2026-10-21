package io.github.l6desk

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.l6desk.recordings.RecordingSaver
import io.github.l6desk.recordings.SavedFile
import java.io.File
import java.io.OutputStream

/**
 * Where recordings downloaded through the Pi go on Android: the shared Music folder
 * (Music/L6Desk/Recordings/<recording>, through MediaStore, so no storage permission is needed and
 * music apps and file managers see them) on Android 10 and later; on older versions the app's own
 * music folder (Android/data/io.github.l6desk/files/Music/…), which needs no permission either.
 * A file is hidden (pending) until it's complete, and removed if the download fails.
 */
class AndroidRecordingSaver(private val context: Context) : RecordingSaver {
    private val shared = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override val location: String =
        if (shared) "this device's Music/L6Desk/Recordings"
        else File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "L6Desk/Recordings").path

    /** The Music folder takes only audio: the mixer's project file (ALL) isn't kept. */
    override fun create(folder: String, name: String, bytes: Long): SavedFile? = when {
        !name.endsWith(".wav", ignoreCase = true) && shared -> null
        shared -> mediaStore(folder, name)
        else -> plainFile(folder, name)
    }

    private fun mediaStore(folder: String, name: String): SavedFile {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val path = "${Environment.DIRECTORY_MUSIC}/L6Desk/Recordings/$folder/"
        // A file of the same name from an earlier download is replaced, not kept as "name (1)".
        resolver.delete(
            collection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(path, name),
        )
        val uri = requireNotNull(
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ),
        ) { "Couldn't save $name in the Music folder" }
        val out: OutputStream = requireNotNull(resolver.openOutputStream(uri)) { "Couldn't save $name in the Music folder" }.buffered(1 shl 16)
        return object : SavedFile {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun finish() {
                out.close()
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            override fun abort() {
                runCatching { out.close() }
                resolver.delete(uri, null, null)
            }
        }
    }

    private fun plainFile(folder: String, name: String): SavedFile {
        val dir = File(location, folder).apply { mkdirs() }
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        val out = part.outputStream().buffered(1 shl 16)
        return object : SavedFile {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun finish() {
                out.close()
                target.delete()
                check(part.renameTo(target)) { "Couldn't save $name" }
            }
            override fun abort() {
                runCatching { out.close() }
                part.delete()
            }
        }
    }
}

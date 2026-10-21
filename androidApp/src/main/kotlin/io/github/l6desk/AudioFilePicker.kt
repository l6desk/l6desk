package io.github.l6desk

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.l6desk.player.UploadSource
import io.github.l6desk.player.isAudioFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Picks music files to upload to the bridge with Android's document picker (no storage
 * permission needed). Create it before the activity starts, as Android requires.
 */
class AudioFilePicker(private val activity: ComponentActivity) {
    private var pending: CompletableDeferred<List<Uri>>? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pending?.complete(uris)
        pending = null
    }

    suspend fun pick(): List<UploadSource> {
        val result = CompletableDeferred<List<Uri>>()
        pending?.cancel()
        pending = result
        launcher.launch(arrayOf("audio/*"))
        val resolver = activity.contentResolver
        return result.await().mapNotNull { uri -> describe(resolver, uri) }
    }

    private fun describe(resolver: ContentResolver, uri: Uri): UploadSource? {
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        // Some providers leave the extension off the name; the bridge goes by it, so add one from the type.
        val shown = name ?: uri.lastPathSegment ?: return null
        val fileName = if (isAudioFile(shown)) shown else extensionFor(resolver.getType(uri))?.let { "$shown.$it" } ?: return null
        return UriSource(resolver, uri, fileName, size)
    }

    private fun extensionFor(type: String?): String? = when (type?.lowercase()) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac", "audio/aacp" -> "m4a"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
        else -> null
    }

    private class UriSource(
        private val resolver: ContentResolver,
        private val uri: Uri,
        override val name: String,
        override val size: Long,
    ) : UploadSource {
        private var stream: InputStream? = null

        override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
            val s = stream ?: (resolver.openInputStream(uri) ?: error("Can't open $name")).also { stream = it }
            s.read(buffer)
        }

        override fun close() {
            runCatching { stream?.close() }
        }
    }
}

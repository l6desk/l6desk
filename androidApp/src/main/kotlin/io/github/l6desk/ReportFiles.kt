package io.github.l6desk

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * REPORT on Android: saves the report zip with the system's "save to" picker (no storage
 * permission needed), or hands it to another app through the share sheet. Create it before the
 * activity starts, as Android requires.
 */
class ReportFiles(private val activity: ComponentActivity) {
    private var pending: CompletableDeferred<Uri?>? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        pending?.complete(uri)
        pending = null
    }

    suspend fun save(fileName: String, bytes: ByteArray): String? {
        val result = CompletableDeferred<Uri?>()
        pending?.cancel()
        pending = result
        launcher.launch(fileName)
        val uri = result.await() ?: return null
        val resolver = activity.contentResolver
        withContext(Dispatchers.IO) {
            (resolver.openOutputStream(uri) ?: error("Can't write to the chosen place")).use { it.write(bytes) }
        }
        val shown = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: fileName
        return "\"$shown\" in the folder you chose"
    }

    fun share(fileName: String, bytes: ByteArray) {
        val dir = File(activity.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the latest report is kept
        val file = File(dir, fileName).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.reports", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName.removeSuffix(".zip"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "Send the problem report"))
    }
}

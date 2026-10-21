package io.github.l6desk.recordings

import io.github.l6desk.net.NetworkMidiTransport
import io.github.l6desk.padcard.PadCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * RECORDINGS through the Pi bridge (protocol v8): the L6max, in file transfer mode, is on the Pi's
 * USB; the Pi lists its recordings, converts what's asked for on the way and sends it over Wi-Fi,
 * into [saver] (a folder on the desktop, the tablet's Music folder on Android).
 */
class BridgeRecordingsSupport(private val bridge: NetworkMidiTransport, private val saver: RecordingSaver) : RecordingsSupport {
    override val recordings: StateFlow<List<Recording>?> = bridge.recordings
    override val connectTo = "the Pi"
    override val overWifi = true
    override val destination: String get() = saver.location

    override fun watch(): () -> Unit = {} // the bridge reports changes by itself

    override val canEject: Boolean get() = bridge.padCardOffered.value
    override val canOpenFolder: Boolean get() = saver.canOpenFolder

    override suspend fun download(recording: Recording, options: RecordingOptions, onProgress: (DownloadProgress) -> Unit): String {
        // Off the UI thread (it writes files), and progress at most ten times a second, not per frame.
        var last = TimeSource.Monotonic.markNow()
        val result = withContext(Dispatchers.IO) {
            bridge.downloadRecording(recording, options, saver) { p ->
                if (last.elapsedNow().inWholeMilliseconds >= 100 || p.done == p.total) {
                    last = TimeSource.Monotonic.markNow()
                    onProgress(p)
                }
            }
        }
        val mb = result.bytes / (1024 * 1024)
        val clipped = if (result.clipped.isEmpty()) "" else
            ". ${result.clipped.keys.joinToString(", ")} went over full scale, so 24-bit clipped it: NORMALISE (or 32-BIT FLOAT) keeps it whole."
        val skipped = if (result.skipped.isEmpty()) "" else ". Not kept here: ${result.skipped.joinToString(", ")} (only the mixer uses it)"
        return "${result.files.size} file${if (result.files.size == 1) "" else "s"} ($mb MB) saved in ${saver.location}/${recording.folder}$clipped$skipped"
    }

    override fun openFolder(recording: Recording): Boolean = saver.openFolder(recording.folder)

    override suspend fun eject(): String? {
        if (!bridge.padCardOffered.value) return null
        val result = bridge.padCommand { PadCommand.Eject(it) }
        if (result.error) throw IllegalStateException(result.message)
        return result.message
    }
}

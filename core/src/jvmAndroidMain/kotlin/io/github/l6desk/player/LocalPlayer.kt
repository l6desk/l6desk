package io.github.l6desk.player

import io.github.l6desk.net.RemotePlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The music player on this device (the desktop, or the tablet over USB-OTG), for when the L6max is
 * on its USB rather than behind a Pi: the same [WavPlayer] the Pi runs, with its library in [dir] (playlists are folders; songs
 * copied in by hand are picked up), playing into the L6max's USB audio. Uploads copy the chosen
 * files into the library (converting anything but 48 kHz WAV with ffmpeg, if installed).
 */
class LocalPlayer(
    dir: File,
    scope: CoroutineScope,
    openOutput: () -> PcmOutput,
    outputAvailable: () -> Boolean,
    converter: AudioConverter?,
    outputPairs: () -> Int = { 2 },
    /** Where the L6max must be plugged in, for the panel's hint: "this computer", "this tablet". */
    private val where: String = "this computer",
    private val log: (String) -> Unit = {},
) : PlayerHost {
    private val wav = WavPlayer(dir.apply { mkdirs() }, scope, openOutput, outputAvailable, outputPairs = outputPairs, converter = converter, log = log)

    init {
        log("Music player: ${wav.tracks.value.size} song(s) in ${wav.state.value.playlists.size} playlist(s) in $dir")
    }
    private val ids = AtomicInteger()

    override val player: StateFlow<RemotePlayer?> =
        combine(wav.tracks, wav.state) { tracks, state -> RemotePlayer(tracks, state, canUpload = true) }
            .stateIn(scope, SharingStarted.Eagerly, RemotePlayer(wav.tracks.value, wav.state.value, canUpload = true))

    override val noAudioHint get() = "The L6max's USB audio isn't available on $where: is the L6max plugged in by USB?"

    override fun command(command: PlayerCommand) = wav.handle(command)

    override suspend fun upload(source: UploadSource, playlist: String, onStatus: (UploadStatus) -> Unit): UploadStatus = withContext(Dispatchers.IO) {
        val id = ids.incrementAndGet()
        val result = CompletableDeferred<UploadStatus>()
        val receiver = try {
            wav.startUpload(UploadStart(id, playlist, source.name, source.size)) { status ->
                onStatus(status)
                if (status.stage == UploadStage.DONE || status.stage == UploadStage.FAILED) result.complete(status)
            }
        } catch (e: Exception) {
            return@withContext UploadStatus(id, UploadStage.FAILED, message = e.message ?: "Can't add ${source.name}").also(onStatus)
        }
        try {
            val buffer = ByteArray(64 * 1024)
            var received = 0L
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                receiver.write(buffer, 0, n)
                received += n
                onStatus(UploadStatus(id, UploadStage.RECEIVING, received))
            }
            receiver.finish()
            result.await()
        } catch (e: CancellationException) {
            receiver.abort()
            throw e
        } catch (e: Exception) {
            receiver.abort()
            UploadStatus(id, UploadStage.FAILED, message = "Couldn't copy ${source.name}: ${e.message}").also(onStatus)
        }
    }

    /** Stops playback for good (the app is closing). */
    fun shutdown() = wav.shutdown()

    companion object {
        /** ~/Music/L6Desk (on Windows, the user's Music folder too). */
        fun defaultDir(): File = File(File(System.getProperty("user.home"), "Music"), "L6Desk")
    }
}

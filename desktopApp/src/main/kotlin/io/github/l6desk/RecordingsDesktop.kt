package io.github.l6desk

import io.github.l6desk.padcard.PadCard
import io.github.l6desk.recordings.DownloadProgress
import io.github.l6desk.recordings.RecorderCard
import io.github.l6desk.recordings.Recording
import io.github.l6desk.recordings.RecordingCopier
import io.github.l6desk.recordings.RecordingOptions
import io.github.l6desk.recordings.RecordingsSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * RECORDINGS on the desktop: the L6max's card mounted here (file transfer mode, as for PAD
 * SOUNDS), its recordings listed newest first and their tracks measured in the background, and
 * downloads into ~/Music/L6Desk/Recordings/<recording>. Nothing on the card is changed.
 */
class RecordingsDesktop(private val scope: CoroutineScope, private val ejectCard: suspend () -> String?) : RecordingsSupport {
    private val _recordings = MutableStateFlow<List<Recording>?>(null)
    override val recordings: StateFlow<List<Recording>?> = _recordings.asStateFlow()

    private val base = File(File(System.getProperty("user.home"), "Music"), "L6Desk/Recordings")
    override val destination: String = base.path

    /** Measured recordings, by folder and file sizes (so a new bounce is measured again). */
    private val measured = mutableMapOf<String, Recording>()

    override fun watch(): () -> Unit {
        val job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                refresh()
                delay(2_000)
            }
        }
        return { job.cancel() }
    }

    private fun card(): RecorderCard? = PadCard.find()?.root?.takeIf(RecorderCard::isCard)?.let(::RecorderCard)

    private fun key(r: Recording) = r.folder + r.files.joinToString { "${it.name}:${it.bytes}" }

    private fun refresh() {
        val card = card()
        if (card == null) {
            _recordings.value = null
            return
        }
        val list = runCatching { card.list() }.getOrNull() ?: return
        _recordings.value = list.map { measured[key(it)] ?: it }
        // Measure what isn't yet, newest first, showing each as it's done.
        for (r in list) {
            if (measured.containsKey(key(r))) continue
            val m = runCatching { card.measure(r) }.getOrNull() ?: continue
            measured[key(r)] = m
            _recordings.value = _recordings.value?.map { if (it.folder == m.folder) m else it }
        }
    }

    private fun folderFor(r: Recording) = File(base, r.folder)

    override suspend fun download(recording: Recording, options: RecordingOptions, onProgress: (DownloadProgress) -> Unit): String = withContext(Dispatchers.IO) {
        val card = card() ?: error("The L6max's card isn't connected any more")
        val current = measured[key(recording)] ?: card.measure(recording)
        val result = RecordingCopier(card).copy(current, options, folderFor(recording), onProgress)
        val files = result.files
        val mb = files.sumOf { it.length() } / (1024 * 1024)
        "${files.size} file${if (files.size == 1) "" else "s"} ($mb MB) saved in ${folderFor(recording).path}" + clippedNote(current, result.clipped)
    }

    /** Says which files went over full scale and were clipped in 24-bit, and what avoids it. */
    private fun clippedNote(r: Recording, clipped: Map<String, Long>): String {
        if (clipped.isEmpty()) return ""
        val which = clipped.keys.joinToString(", ") { n -> r.files.firstOrNull { it.name == n }?.label ?: n }
        return ". $which went over full scale, so 24-bit clipped it: NORMALISE (or 32-BIT FLOAT) keeps it whole."
    }

    override fun openFolder(recording: Recording): Boolean {
        val dir = folderFor(recording).takeIf { it.isDirectory } ?: return false
        return runCatching {
            Thread { runCatching { java.awt.Desktop.getDesktop().open(dir) } }.start()
            true
        }.getOrDefault(false)
    }

    override suspend fun eject(): String? = ejectCard()
}

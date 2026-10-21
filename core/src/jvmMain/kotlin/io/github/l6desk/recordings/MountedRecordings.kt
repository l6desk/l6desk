package io.github.l6desk.recordings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** The bridge end of RECORDINGS (protocol v8): the card's recordings, and downloads from them. */
interface RecordingsHost {
    /** The recordings, newest first, while the card is there; null while it isn't. */
    val recordings: StateFlow<List<Recording>?>

    /** Works out what downloading [folder] with [options] sends ([onRead]: done, total, while normalising reads first). */
    suspend fun prepare(folder: String, options: RecordingOptions, onRead: (Long, Long) -> Unit): PreparedDownload
}

/** A download worked out by [RecordingsHost.prepare]: its [files], each written in turn. */
interface PreparedDownload {
    val files: List<RecFileInfo>

    /** Writes file [index] (exactly its size) to [sink]; returns how many samples were clipped. */
    suspend fun write(index: Int, sink: ByteSink): Long
}

/**
 * RECORDINGS at the Pi: the L6max's card as mounted by the Pi (the same mount as PAD SOUNDS,
 * [MountedPadCard][io.github.l6desk.padcard.MountedPadCard]), looked at every [pollMs], each
 * recording's tracks measured once. Read only.
 */
class MountedRecordings(private val mount: File, private val pollMs: Long = 2_000, private val log: (String) -> Unit = {}) : RecordingsHost {
    private val _recordings = MutableStateFlow<List<Recording>?>(null)
    override val recordings: StateFlow<List<Recording>?> = _recordings.asStateFlow()
    private val measured = java.util.concurrent.ConcurrentHashMap<String, Recording>()
    private val peaks = java.util.concurrent.ConcurrentHashMap<String, Float>()

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                refresh()
                delay(pollMs)
            }
        }
    }

    private fun card(): RecorderCard? = mount.takeIf(RecorderCard::isCard)?.let(::RecorderCard)

    private fun key(r: Recording) = r.folder + r.files.joinToString { "${it.name}:${it.bytes}" }

    /** Looks at the card again, measuring recordings not yet measured (newest first). */
    fun refresh() {
        val card = card()
        if (card == null) {
            _recordings.value = null
            return
        }
        val list = runCatching { card.list() }.getOrNull() ?: return
        _recordings.value = list.map { measured[key(it)] ?: it }
        for (r in list) {
            if (measured.containsKey(key(r))) continue
            val m = runCatching { card.measure(r) }.getOrNull() ?: continue
            measured[key(r)] = m
            _recordings.value = _recordings.value?.map { if (it.folder == m.folder) m else it }
        }
    }

    override suspend fun prepare(folder: String, options: RecordingOptions, onRead: (Long, Long) -> Unit): PreparedDownload {
        val card = card() ?: error("The L6max's card isn't connected to the Pi any more")
        val listed = card.list().firstOrNull { it.folder == folder } ?: error("That recording isn't on the card any more")
        val recording = measured[key(listed)] ?: card.measure(listed)
        val copier = RecordingCopier(card, peakCache = peaks)
        val total = copier.planBytes(recording, options)
        var done = 0L
        val items = copier.plan(recording, options) { _, n ->
            done += n
            onRead(done, total)
        }
        log("Sending recording $folder: ${items.joinToString { it.target }}")
        return object : PreparedDownload {
            override val files = items.map { RecFileInfo(it.target, it.bytes) }
            override suspend fun write(index: Int, sink: ByteSink): Long = copier.write(recording, items[index], sink) {}
        }
    }
}

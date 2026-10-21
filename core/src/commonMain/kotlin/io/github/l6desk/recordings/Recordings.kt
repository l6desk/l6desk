package io.github.l6desk.recordings

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A recording on the L6max's memory card (docs/hardware-notes.md): a folder in `RECORDER/` named
 * when recording started (`261016_104237`), holding every track whether used or not
 * (`TRACK01_01.wav`… mono, `TRACK05_ST_01.wav`… stereo), the master recorded alongside
 * (`MASTER_01.wav`) and one more master take per bounce (`MASTER_02.wav`…).
 */
@Serializable
data class Recording(
    /** The folder's name, which says when it started: yymmdd_hhmmss. */
    val folder: String,
    val durationMs: Long?,
    val files: List<RecordedFile>,
    /** Every file in the folder and their total size (ALL copies them all). */
    val totalBytes: Long,
) {
    /** The newest master take (the last bounce), or null. */
    val master: RecordedFile? get() = files.filter { it.channel == 0 }.maxByOrNull { it.take }
    val masterTakes: Int get() = files.count { it.channel == 0 }
    val tracks: List<RecordedFile> get() = files.filter { it.channel > 0 }.sortedBy { it.channel }

    /** "16 Oct 2026, 10:42", or the folder's name if it isn't a date and time. */
    val title: String get() = titleOf(folder)

    companion object {
        private val NAME = Regex("""(\d{2})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})""")
        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        fun titleOf(folder: String): String {
            val m = NAME.matchEntire(folder) ?: return folder
            val (y, mo, d, h, mi) = m.destructured
            val month = MONTHS.getOrNull(mo.toInt() - 1) ?: return folder
            return "${d.toInt()} $month 20$y, $h:$mi"
        }
    }
}

/**
 * One WAV in a recording: [channel] 1–8 for a track, 0 for a master take; [take] its number
 * (`_02`); [peakDb] its loudest sample found (dBFS, from samples spread through the file), or null
 * until looked at. A track is "used" when that's above [USED_DB]: unused inputs are recorded too.
 */
@Serializable
data class RecordedFile(
    val name: String,
    val channel: Int,
    val stereo: Boolean,
    val take: Int,
    val bytes: Long,
    val peakDb: Double? = null,
) {
    val used: Boolean get() = channel == 0 || (peakDb ?: Double.NEGATIVE_INFINITY) > USED_DB

    /** "CH1", "CH5 (stereo)", "Master take 2". */
    val label: String get() = if (channel == 0) "Master take $take" else "CH$channel"

    companion object {
        /** Unused inputs measured −50 to −87 dBFS on the real mixer; a vocal −3, a backing track −12. */
        const val USED_DB = -40.0
        private val TRACK = Regex("""TRACK(\d{2})(_ST)?_(\d{2})\.WAV""", RegexOption.IGNORE_CASE)
        private val MASTER = Regex("""MASTER_(\d{2})\.WAV""", RegexOption.IGNORE_CASE)

        /** Reads a recording's file name; null for anything else (e.g. the project file). */
        fun parse(name: String, bytes: Long): RecordedFile? {
            TRACK.matchEntire(name)?.let { m ->
                return RecordedFile(name, m.groupValues[1].toInt(), m.groupValues[2].isNotEmpty(), m.groupValues[3].toInt(), bytes)
            }
            MASTER.matchEntire(name)?.let { m -> return RecordedFile(name, 0, true, m.groupValues[1].toInt(), bytes) }
            return null
        }
    }
}

/** What DOWNLOAD copies: the newest master take, the tracks with sound (and that master), or every file. */
@Serializable
enum class RecordingSelection(val label: String) { MASTER("MASTER"), USED("USED TRACKS"), ALL("ALL") }

/** As recorded (32-bit float), or 24-bit PCM (a quarter smaller). */
@Serializable
enum class RecordingFormat(val label: String) { FLOAT32("32-BIT FLOAT"), PCM24("24-BIT") }

/**
 * How recordings are downloaded (remembered). [normalise]: the master's peak raised (or lowered) to
 * [NORMALISED_DB]; the tracks all get the same gain, from the loudest one, so their balance stays.
 */
@Serializable
data class RecordingOptions(
    val selection: RecordingSelection = RecordingSelection.MASTER,
    val format: RecordingFormat = RecordingFormat.FLOAT32,
    val normalise: Boolean = false,
) {
    companion object {
        const val NORMALISED_DB = -1.0
    }
}

/** A download in progress: [done] of [total] bytes (read and written), on [file]. */
data class DownloadProgress(val file: String, val done: Long, val total: Long) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
}

/** What the RECORDINGS panel needs from the platform: this computer's USB, or the Pi bridge's. */
interface RecordingsSupport {
    /** Where the mixer has to be connected by USB: "this computer", "the Pi". */
    val connectTo: String get() = "this computer"

    /** Downloads come over the Pi's Wi-Fi (slower than USB: the panel says so). */
    val overWifi: Boolean get() = false

    /** The card's recordings, newest first, while it's found; null while there's no card. */
    val recordings: StateFlow<List<Recording>?>

    /** Looks for the card (and measures the tracks) until the returned function is called. */
    fun watch(): () -> Unit

    /** Copies [recording]'s files as [options] say; a sentence for the user. Cancellable. */
    suspend fun download(recording: Recording, options: RecordingOptions, onProgress: (DownloadProgress) -> Unit): String

    /** Where downloads go, to show. */
    val destination: String

    /** Opens the folder [recording] was downloaded to, if this platform can; false if not. */
    fun openFolder(recording: Recording): Boolean = false

    /** Whether [openFolder] does anything here (not on Android). */
    val canOpenFolder: Boolean get() = true

    /** Ejects the card, if this computer can; a sentence, or null where it can't. */
    suspend fun eject(): String? = null

    /** Whether EJECT CARD is offered (a Pi bridge set up for the card; any desktop). */
    val canEject: Boolean get() = true
}

/**
 * Where the app saves recordings downloaded through the Pi: a folder on the desktop, the tablet's
 * Music folder on Android. Each file is written in order, then finished (or abandoned).
 */
interface RecordingSaver {
    /** Where they go, to show. */
    val location: String

    /**
     * Starts saving [name] (of [bytes] bytes) for recording [folder]; null if this platform can't
     * keep that kind of file (Android's Music folder takes only audio, not the project file).
     */
    fun create(folder: String, name: String, bytes: Long): SavedFile?

    /** Opens the folder recording [folder] was saved in, if this platform can. */
    fun openFolder(folder: String): Boolean = false

    val canOpenFolder: Boolean get() = false
}

interface SavedFile {
    fun write(bytes: ByteArray, offset: Int, length: Int)

    /** Complete: make it visible under its name. */
    fun finish()

    /** Cancelled or failed: remove what was written. */
    fun abort()
}

/** App → bridge (protocol v8): download a recording, acknowledge data, or give up. [id] names the download. */
@Serializable
sealed interface RecCommand {
    val id: Int

    @Serializable @SerialName("start")
    data class Start(override val id: Int, val folder: String, val options: RecordingOptions) : RecCommand

    /** [received] bytes of the download have arrived (all files together). */
    @Serializable @SerialName("ack")
    data class Ack(override val id: Int, val received: Long) : RecCommand

    @Serializable @SerialName("cancel")
    data class Cancel(override val id: Int) : RecCommand
}

@Serializable
enum class RecStage { PREPARING, SENDING, DONE, FAILED }

/** A file the bridge is about to send: its name once downloaded, and its size. */
@Serializable
data class RecFileInfo(val name: String, val bytes: Long)

/**
 * Bridge → app (v8): how a download is going. PREPARING: the bridge is reading the files first
 * (normalising), [done] of [total] bytes; SENDING: [files] follow in order, as data frames; DONE
 * (with any [clipped] samples per file) or FAILED ([message]).
 */
@Serializable
data class RecState(
    val id: Int,
    val stage: RecStage,
    val files: List<RecFileInfo> = emptyList(),
    val done: Long = 0,
    val total: Long = 0,
    val message: String? = null,
    val clipped: Map<String, Long> = emptyMap(),
)

/** The JSON in the bridge protocol's recording frames. */
object RecordingsJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true // a silent track's peak is −∞
    }
    private val list = ListSerializer(Recording.serializer())

    /** "null" while there's no card. */
    fun encodeList(recordings: List<Recording>?): String = if (recordings == null) "null" else json.encodeToString(list, recordings)
    fun decodeList(text: String): List<Recording>? = if (text.trim() == "null") null else json.decodeFromString(list, text)
    fun encodeCommand(c: RecCommand): String = json.encodeToString(RecCommand.serializer(), c)
    fun decodeCommand(text: String): RecCommand = json.decodeFromString(RecCommand.serializer(), text)
    fun encodeState(s: RecState): String = json.encodeToString(RecState.serializer(), s)
    fun decodeState(text: String): RecState = json.decodeFromString(RecState.serializer(), text)
}

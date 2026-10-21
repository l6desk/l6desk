package io.github.l6desk.player

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What happens when a track reaches its end. */
@Serializable
enum class TrackEnd(val label: String) {
    STOP("Stop"),
    NEXT("Play next"),
    LOOP("Loop"),
}

/** Where the player's audio goes: USB 1/2 is heard on CH7, USB 3/4 on CH8 (when switched to USB). */
@Serializable
enum class PlayerOutput(val channel: Int, val usb: String) {
    CH7(7, "USB 1/2"),
    CH8(8, "USB 3/4"),
}

/**
 * A song at the bridge. [file] is its path in the music folder ("The Beatles/01 Yesterday.wav"),
 * which identifies it; [title] is its name without the order number the bridge keeps it under.
 */
@Serializable
data class Track(
    val file: String,
    val title: String,
    val durationMs: Long,
    val end: TrackEnd = TrackEnd.STOP,
    val playlist: String = "",
)

/** The player at the bridge, as the app shows it. */
@Serializable
data class PlayerState(
    /** The L6max's USB audio is available at the bridge. */
    val ready: Boolean = false,
    /** The track playing (or paused), or null when stopped. */
    val playing: String? = null,
    val positionMs: Long = 0,
    val paused: Boolean = false,
    val output: PlayerOutput = PlayerOutput.CH7,
    /** The track ready to play next (after a track stops, the one after it). */
    val cued: String? = null,
    val error: String? = null,
    /** 0–[MAX_VOLUME], percent of the file's own level (never boosted). */
    val volume: Int = MAX_VOLUME,
    /** The playlists (folders at the bridge), in name order. */
    val playlists: List<String> = emptyList(),
    /** The playlist shown in the app (and that uploads go to). */
    val playlist: String? = null,
    /** The bridge can rename and delete playlists, and rename songs (0.15 on); older bridges leave it false. */
    val canEditPlaylists: Boolean = false,
    /** USB channel pairs the player can play on: 2 (USB 1/2 → CH7, USB 3/4 → CH8), or 1 (only CH7, e.g. on Windows). */
    val usbPairs: Int = 2,
)

const val MAX_VOLUME = 100

/**
 * While the player is in use, the channels it can play on are at full level and the player's own
 * volume does the work, so its slider covers the channel's whole range. It starts quiet.
 */
const val PLAYER_CHANNEL_LEVEL = 127
const val PLAYER_START_VOLUME = 10

/** The gain for [volume]: squared, so the control feels even across its travel (50 ≈ −12 dB). */
fun volumeGain(volume: Int): Float {
    val v = volume.coerceIn(0, MAX_VOLUME) / MAX_VOLUME.toFloat()
    return v * v
}

/** App → bridge. */
@Serializable
sealed interface PlayerCommand {
    @Serializable @SerialName("play")
    data class Play(val file: String) : PlayerCommand

    @Serializable @SerialName("pause")
    data object Pause : PlayerCommand

    @Serializable @SerialName("resume")
    data object Resume : PlayerCommand

    @Serializable @SerialName("stop")
    data object Stop : PlayerCommand

    @Serializable @SerialName("output")
    data class SetOutput(val output: PlayerOutput) : PlayerCommand

    @Serializable @SerialName("end")
    data class SetEnd(val file: String, val end: TrackEnd) : PlayerCommand

    @Serializable @SerialName("cue")
    data class Cue(val file: String) : PlayerCommand

    @Serializable @SerialName("volume")
    data class SetVolume(val volume: Int) : PlayerCommand

    @Serializable @SerialName("playlist")
    data class SelectPlaylist(val name: String) : PlayerCommand

    @Serializable @SerialName("new-playlist")
    data class CreatePlaylist(val name: String) : PlayerCommand

    /** Moves a song [by] places in its playlist (−1 = up one). */
    @Serializable @SerialName("move")
    data class Move(val file: String, val by: Int) : PlayerCommand

    @Serializable @SerialName("delete")
    data class Delete(val file: String) : PlayerCommand

    /** Only sent when [PlayerState.canEditPlaylists]; an older bridge can't read it, and ignores it. */
    @Serializable @SerialName("rename-playlist")
    data class RenamePlaylist(val name: String, val to: String) : PlayerCommand

    /** Renames a song (its title; its place in the playlist stays). Only sent when [PlayerState.canEditPlaylists]. */
    @Serializable @SerialName("rename")
    data class Rename(val file: String, val title: String) : PlayerCommand

    /** Deletes the playlist and its songs (stopping the song playing, if it's one of them). */
    @Serializable @SerialName("delete-playlist")
    data class DeletePlaylist(val name: String) : PlayerCommand
}

/** The music files the bridge takes. Anything but 48 kHz 16-bit WAV is converted when it arrives. */
val AUDIO_EXTENSIONS = setOf("wav", "mp3", "m4a", "aac", "flac")

fun isAudioFile(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

/** App → bridge (v3): a file is about to be sent, to be added to the end of [playlist]. [size] is −1 if unknown. */
@Serializable
data class UploadStart(val id: Int, val playlist: String, val name: String, val size: Long = -1)

@Serializable
enum class UploadStage { RECEIVING, CONVERTING, DONE, FAILED }

/** Bridge → app (v3): how an upload is going. [received] bytes so far; [file] is the song it became. */
@Serializable
data class UploadStatus(
    val id: Int,
    val stage: UploadStage,
    val received: Long = 0,
    val message: String? = null,
    val file: String? = null,
)

/** The bridge end of one upload. */
interface UploadReceiver {
    fun write(bytes: ByteArray, offset: Int, length: Int)

    /** All bytes are in: convert and add the song (reported through the start's status callback). */
    fun finish()
    fun abort()
}

/** A file picked for upload (from the desktop's file chooser, or Android's document picker). */
interface UploadSource {
    val name: String

    /** In bytes, or −1 if unknown. */
    val size: Long

    /** Reads into [buffer]; the number of bytes read, or −1 at the end. */
    suspend fun read(buffer: ByteArray): Int
    fun close()
}

/**
 * A music player at the bridge end (the headless bridge plays WAV files from its music folder into
 * the L6max's USB audio). The bridge sends [tracks] and [state] to the app, and passes the app's
 * commands to [handle].
 */
interface MusicPlayer {
    val tracks: StateFlow<List<Track>>
    val state: StateFlow<PlayerState>
    fun handle(command: PlayerCommand)

    /**
     * Starts receiving an upload; [onStatus] reports conversion and the result (from any thread).
     * Throws with a message for the app if it can't be taken.
     */
    fun startUpload(start: UploadStart, onStatus: (UploadStatus) -> Unit): UploadReceiver =
        throw UnsupportedOperationException("This bridge can't take uploads")
}

/** The JSON carried in the bridge protocol's player frames. */
object PlayerJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val trackList = ListSerializer(Track.serializer())

    fun encodeTracks(tracks: List<Track>): String = json.encodeToString(trackList, tracks)
    fun decodeTracks(text: String): List<Track> = json.decodeFromString(trackList, text)
    fun encodeState(state: PlayerState): String = json.encodeToString(PlayerState.serializer(), state)
    fun decodeState(text: String): PlayerState = json.decodeFromString(PlayerState.serializer(), text)
    fun encodeCommand(command: PlayerCommand): String = json.encodeToString(PlayerCommand.serializer(), command)
    fun decodeCommand(text: String): PlayerCommand = json.decodeFromString(PlayerCommand.serializer(), text)
    fun encodeUploadStart(start: UploadStart): String = json.encodeToString(UploadStart.serializer(), start)
    fun decodeUploadStart(text: String): UploadStart = json.decodeFromString(UploadStart.serializer(), text)
    fun encodeUploadStatus(status: UploadStatus): String = json.encodeToString(UploadStatus.serializer(), status)
    fun decodeUploadStatus(text: String): UploadStatus = json.decodeFromString(UploadStatus.serializer(), text)
}

/** "3:42" */
fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

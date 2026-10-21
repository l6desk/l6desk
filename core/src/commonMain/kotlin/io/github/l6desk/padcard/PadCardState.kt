package io.github.l6desk.padcard

import io.github.l6desk.player.Track
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The L6max's memory card, seen in its file transfer mode (it appears as a USB drive), for setting
 * the sound pads' sounds: [location] is where it's mounted, [pads] pads 1–4.
 */
@Serializable
data class PadCardState(val location: String, val pads: List<PadSound>)

/**
 * A pad's sound on the card: [file] the one the mixer plays (its assigned file, or the only file in
 * the pad's folder), [durationMs] its length (null if unknown), [others] other WAVs in the folder.
 */
@Serializable
data class PadSound(val pad: Int, val file: String?, val durationMs: Long? = null, val others: Int = 0)

/** What the PAD SOUNDS panel needs from the platform: this computer's USB, or the Pi bridge's. */
interface PadCardSupport {
    /** The card while one is found (looked for every couple of seconds while [watch] runs), else null. */
    val card: StateFlow<PadCardState?>

    /** Looks for the card until the returned function is called (while the panel is open). */
    fun watch(): () -> Unit

    /** Lets the user pick a sound for [pad] and puts it on the card; a sentence for the user, or null if cancelled. */
    suspend fun choose(pad: Int): String?

    /** Removes [pad]'s sound (after backing it up); a sentence for the user. */
    suspend fun clear(pad: Int): String

    /** Ejects the card, if this computer can (Ubuntu); a sentence for the user, or null where it can't. */
    suspend fun eject(): String?

    /** Where removed sounds are backed up, to show. */
    val backupLocation: String

    /** Where the mixer has to be connected by USB: "this computer", "the Pi". */
    val connectTo: String get() = "this computer"

    /**
     * Songs to choose a pad's sound from, when they're picked in the panel (the Pi's music
     * library) rather than with [choose]'s file dialog; null for the file dialog.
     */
    val library: StateFlow<List<Track>>? get() = null

    /** Makes [track] (from [library]) pad [pad]'s sound; a sentence for the user. */
    suspend fun chooseTrack(pad: Int, track: Track): String = throw UnsupportedOperationException("No library here")
}

/** App → bridge (protocol v7): change the card at the bridge. [id] matches the [PadResult]. */
@Serializable
sealed interface PadCommand {
    val id: Int

    /** Makes the library song [file] (a [Track.file]) pad [pad]'s sound. */
    @Serializable @SerialName("set")
    data class Set(override val id: Int, val pad: Int, val file: String) : PadCommand

    @Serializable @SerialName("clear")
    data class Clear(override val id: Int, val pad: Int) : PadCommand

    @Serializable @SerialName("eject")
    data class Eject(override val id: Int) : PadCommand
}

/** Bridge → app (v7): how [PadCommand] [id] went, as a sentence for the user. */
@Serializable
data class PadResult(val id: Int, val message: String, val error: Boolean = false)

/** The JSON carried in the bridge protocol's pad card frames. */
object PadCardJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** "null" while there's no card. */
    fun encodeState(state: PadCardState?): String = if (state == null) "null" else json.encodeToString(PadCardState.serializer(), state)
    fun decodeState(text: String): PadCardState? = if (text.trim() == "null") null else json.decodeFromString(PadCardState.serializer(), text)
    fun encodeCommand(command: PadCommand): String = json.encodeToString(PadCommand.serializer(), command)
    fun decodeCommand(text: String): PadCommand = json.decodeFromString(PadCommand.serializer(), text)
    fun encodeResult(result: PadResult): String = json.encodeToString(PadResult.serializer(), result)
    fun decodeResult(text: String): PadResult = json.decodeFromString(PadResult.serializer(), text)
}

/** The bridge end of PAD SOUNDS (the headless bridge, with the mixer's card mounted on the Pi). */
interface PadCardHost {
    /** The card while it's mounted, else null. */
    val card: StateFlow<PadCardState?>

    /** Carries out [command] (blocking: run it off the session's reader). */
    fun handle(command: PadCommand): PadResult
}

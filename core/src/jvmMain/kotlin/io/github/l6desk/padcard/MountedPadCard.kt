package io.github.l6desk.padcard

import io.github.l6desk.player.AudioConverter
import io.github.l6desk.player.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * PAD SOUNDS at the Pi bridge: the L6max's card, mounted at [mount] by the Pi when the mixer is in
 * file transfer mode (90-l6desk-card.rules), looked for every [pollMs]. A pad's sound comes from
 * the music library at [library] (one of [tracks]: already 48 kHz WAV); replaced sounds are backed
 * up under [backups]. Ejecting creates [ejectRequest], which the root-run `l6desk-card-eject.path`
 * unit acts on (the bridge can't unmount anything itself).
 */
class MountedPadCard(
    private val mount: File,
    private val library: File?,
    private val tracks: () -> List<Track>,
    private val backups: File?,
    private val ejectRequest: File,
    private val converter: AudioConverter? = null,
    private val pollMs: Long = 2_000,
    private val log: (String) -> Unit = {},
) : PadCardHost {
    private val _card = MutableStateFlow<PadCardState?>(null)
    override val card: StateFlow<PadCardState?> = _card.asStateFlow()
    private val lock = Any()

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            var was = false
            while (isActive) {
                val now = refresh()
                if (now != was) log(if (now) "The L6max's card is mounted (file transfer mode): PAD SOUNDS can change the pads" else "The L6max's card has gone")
                was = now
                delay(pollMs)
            }
        }
    }

    /** Looks at the card again; whether there is one. */
    fun refresh(): Boolean = synchronized(lock) {
        val state = runCatching { if (PadCard.isCard(mount)) PadCard(mount).state().copy(location = "at the Pi") else null }.getOrNull()
        _card.value = state
        state != null
    }

    override fun handle(command: PadCommand): PadResult = synchronized(lock) {
        val result = try {
            when (command) {
                is PadCommand.Set -> {
                    val track = tracks().firstOrNull { it.file == command.file } ?: error("That song isn't on the Pi any more")
                    val dir = library ?: error("This bridge has no music library")
                    val source = File(dir, track.file)
                    require(source.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath()) && source.isFile) { "That song isn't on the Pi any more" }
                    val card = current()
                    card.setSound(command.pad, source, track.title, converter, PadCard.backupFolder(backupDir()))
                    log("Pad ${command.pad}: \"${track.title}\" from ${track.playlist}")
                    PadResult(command.id, "${PadCard.fatSafe(track.title)}.wav is on the card for pad ${command.pad}. When you've finished, EJECT CARD before switching off file transfer mode.")
                }
                is PadCommand.Clear -> {
                    current().clear(command.pad, PadCard.backupFolder(backupDir()))
                    log("Pad ${command.pad}: sound removed")
                    PadResult(command.id, "Pad ${command.pad} has no sound now.")
                }
                is PadCommand.Eject -> eject(command.id)
            }
        } catch (e: Exception) {
            PadResult(command.id, e.message ?: "That didn't work", error = true)
        }
        refresh()
        result
    }

    private fun current(): PadCard {
        check(PadCard.isCard(mount)) { "The L6max's card isn't connected to the Pi any more" }
        return PadCard(mount)
    }

    private fun backupDir(): File = backups ?: error("This bridge has nowhere to back up the pads' old sounds")

    /** Asks the root helper to unmount the card, and waits (up to 15 s) for it to go. */
    private fun eject(id: Int): PadResult {
        if (!PadCard.isCard(mount)) return PadResult(id, "The card isn't connected.")
        runCatching { PadCard(mount).flush() }
        check(runCatching { ejectRequest.writeText("Requested by the app\n") }.isSuccess) { "Couldn't ask the Pi to eject the card" }
        repeat(30) {
            Thread.sleep(500)
            if (!PadCard.isCard(mount)) {
                log("The L6max's card was ejected")
                return PadResult(id, "Card ejected. Now switch off file transfer mode on the L6max, and its pads play the new sounds.")
            }
        }
        return PadResult(id, "The Pi didn't eject the card: wait a moment before switching off file transfer mode.", error = true)
    }

    companion object {
        const val DEFAULT_MOUNT = "/run/l6desk-card"
        const val DEFAULT_EJECT_REQUEST = "/run/l6desk-bridge/eject-card"

        /** The pads' old sounds, in the music folder (hidden, so the player doesn't list them). */
        const val BACKUPS = ".pad-backups"
    }
}

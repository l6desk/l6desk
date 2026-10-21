package io.github.l6desk.padcard

import io.github.l6desk.net.NetworkMidiTransport
import io.github.l6desk.player.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * PAD SOUNDS through the Pi bridge (protocol v7): the L6max, in file transfer mode, is on the Pi's
 * USB; the Pi mounts its card, and the pads' sounds are picked from the Pi's music library (songs
 * already there, so nothing large crosses Wi-Fi). Works on any platform the app runs on.
 */
class BridgePadCardSupport(private val bridge: NetworkMidiTransport, scope: CoroutineScope) : PadCardSupport {
    override val card: StateFlow<PadCardState?> = bridge.padCard

    override val library: StateFlow<List<Track>> =
        bridge.player.map { it?.tracks.orEmpty() }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val connectTo = "the Pi"

    override val backupLocation = "the Pi's music folder (.pad-backups)"

    override fun watch(): () -> Unit = {} // the bridge reports changes by itself

    /** Songs are picked in the panel ([library]), not with a file dialog. */
    override suspend fun choose(pad: Int): String? = null

    override suspend fun chooseTrack(pad: Int, track: Track): String = run { PadCommand.Set(it, pad, track.file) }

    override suspend fun clear(pad: Int): String = run { PadCommand.Clear(it, pad) }

    override suspend fun eject(): String = run { PadCommand.Eject(it) }

    private suspend fun run(make: (Int) -> PadCommand): String {
        val result = bridge.padCommand(make = make)
        if (result.error) throw IllegalStateException(result.message)
        return result.message
    }
}

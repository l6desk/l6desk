package io.github.l6desk.player

import io.github.l6desk.net.RemotePlayer
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the PLAYER panel's songs live and play: the Pi bridge (over the network), or this computer
 * when the L6max is on its USB (`LocalPlayer`, desktop). The panel works the same either way.
 */
interface PlayerHost {
    /** The player's songs and state, or null when there's none (e.g. not connected). */
    val player: StateFlow<RemotePlayer?>

    fun command(command: PlayerCommand)

    /** Adds [source] to the end of [playlist]; [onStatus] reports progress. */
    suspend fun upload(source: UploadSource, playlist: String, onStatus: (UploadStatus) -> Unit = {}): UploadStatus

    /** Shown when the L6max's USB audio isn't available there. */
    val noAudioHint: String
}

package io.github.l6desk.net

import kotlinx.coroutines.flow.StateFlow

/** What the desktop's Bridge mode shows: where tablets can connect, and who is connected. */
data class BridgeStatus(
    val running: Boolean = false,
    val port: Int = BridgeProtocol.DEFAULT_PORT,
    val addresses: List<String> = emptyList(),
    val client: String? = null,
    val error: String? = null,
    /** Whether it's announced on the network, so tablets list it (null: not yet, or not tried). */
    val announce: String? = null,
)

/** The bridge server, as the app's UI controls it. Provided only on the desktop. */
interface BridgeHost {
    val status: StateFlow<BridgeStatus>
    fun start(port: Int = BridgeProtocol.DEFAULT_PORT)
    fun stop()

    /** The PIN apps must give to connect (protocol v6); null for none. */
    fun setPin(pin: String?) {}
}

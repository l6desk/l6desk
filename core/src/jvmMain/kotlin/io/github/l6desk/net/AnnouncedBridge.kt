package io.github.l6desk.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The desktop's Bridge mode: [server], announced on the network as [name] once it's listening
 * ([MdnsResponder]), so tablets list it without its address being typed in. If it can't be
 * announced, the bridge still works by address, and [status] says why in [BridgeStatus.announce].
 */
class AnnouncedBridge(
    private val server: BridgeHost,
    private val name: String,
    private val scope: CoroutineScope,
    private val responderFor: (BridgeAnnouncement) -> Responder = { MdnsResponder(it, scope).asResponder() },
) : BridgeHost {
    /** Starts and stops an announcement (an interface, so tests needn't use the real mDNS port). */
    interface Responder {
        fun start()
        fun stop()
    }

    private val announce = MutableStateFlow<String?>(null)
    private var responder: Responder? = null
    private var job: Job? = null

    override val status: StateFlow<BridgeStatus> =
        combine(server.status, announce) { s, a -> if (s.running) s.copy(announce = a) else s }
            .stateIn(scope, SharingStarted.Eagerly, server.status.value)

    override fun start(port: Int) {
        stop()
        server.start(port)
        job = scope.launch {
            val running = server.status.first { it.running || it.error != null }
            if (!running.running) return@launch
            val r = responderFor(BridgeAnnouncement(name, running.port))
            announce.value = try {
                r.start()
                responder = r
                "Announced on the network as \"$name\""
            } catch (e: Exception) {
                "Not announced on the network (${e.message ?: e::class.simpleName}): add it by address"
            }
        }
    }

    override fun setPin(pin: String?) = server.setPin(pin)

    override fun stop() {
        job?.cancel()
        job = null
        responder?.stop()
        responder = null
        announce.value = null
        server.stop()
    }
}

private fun MdnsResponder.asResponder() = object : AnnouncedBridge.Responder {
    override fun start() = this@asResponder.start()
    override fun stop() = this@asResponder.stop()
}

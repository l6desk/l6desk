package io.github.l6desk.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A bridge announcing itself on the network (mDNS `_l6desk-bridge._tcp`). */
data class DiscoveredBridge(val name: String, val host: String, val port: Int) {
    val address: BridgeAddress get() = BridgeAddress(host, port)
}

/** A UDP socket on an ordinary port, for mDNS questions and their (unicast) answers. */
interface DatagramChannel {
    suspend fun send(packet: ByteArray, host: String, port: Int)

    /** The next packet received, or null if none arrived within [timeout]. */
    suspend fun receive(timeout: Duration): ByteArray?

    fun close()
}

/**
 * Finds bridges on the local network: every [interval] it asks for `_l6desk-bridge._tcp` (a
 * legacy-unicast mDNS query, which needs no multicast permissions on Android) and collects the
 * answers. A bridge that misses [missesAllowed] rounds in a row is dropped (within 12–15 s with
 * the defaults; goodbye announcements go to the multicast group, which this doesn't listen to). The headless bridge announces itself through Avahi (bridge/deploy/announce.sh).
 */
class BridgeDiscovery(
    private val scope: CoroutineScope,
    private val openChannel: () -> DatagramChannel,
    private val interval: Duration = 3.seconds,
    private val missesAllowed: Int = 3,
) {
    private val _found = MutableStateFlow<List<DiscoveredBridge>>(emptyList())

    /** Bridges currently found, by name. */
    val found: StateFlow<List<DiscoveredBridge>> = _found.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job == null) job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _found.value = emptyList()
    }

    private suspend fun run() {
        val known = mutableMapOf<String, DiscoveredBridge>()
        val misses = mutableMapOf<String, Int>()
        while (coroutineContext.isActive) {
            val channel = try {
                openChannel()
            } catch (e: Exception) {
                delay(interval) // no network yet
                continue
            }
            try {
                while (coroutineContext.isActive) {
                    for (service in SERVICES) channel.send(Mdns.query(service, Mdns.TYPE_PTR), Mdns.GROUP, Mdns.PORT)
                    val seen = mutableSetOf<String>()
                    withTimeoutOrNull(interval) {
                        while (true) {
                            val packet = channel.receive(250.milliseconds) ?: continue
                            val records = try {
                                Mdns.parse(packet)
                            } catch (e: Mdns.MalformedException) {
                                continue
                            }
                            for (bridge in bridgesIn(records)) {
                                known[bridge.name] = bridge
                                seen += bridge.name
                            }
                            publish(known)
                        }
                    }
                    for (name in known.keys.toList()) {
                        misses[name] = if (name in seen) 0 else (misses[name] ?: 0) + 1
                        if (misses.getValue(name) > missesAllowed) known -= name
                    }
                    misses.keys.retainAll(known.keys)
                    publish(known)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(interval) // e.g. Wi-Fi went away; open a fresh socket
            } finally {
                channel.close()
            }
        }
    }

    private fun publish(known: Map<String, DiscoveredBridge>) {
        _found.value = known.values.sortedBy { it.name.lowercase() }
    }

    companion object {
        const val SERVICE = "_l6desk-bridge._tcp.local"
        /** What bridges before 0.13 (the L6max Remote name) announce; still looked for, so they're found until updated. */
        const val OLD_SERVICE = "_l6max-bridge._tcp.local"
        val SERVICES = listOf(SERVICE, OLD_SERVICE)

        /** The bridges fully described (name, port and IPv4 address) in one reply. */
        fun bridgesIn(records: List<Mdns.Record>): List<DiscoveredBridge> =
            records.filterIsInstance<Mdns.Record.Ptr>().filter { ptr -> ptr.ttl > 0 && SERVICES.any { ptr.name.equals(it, ignoreCase = true) } }.mapNotNull { ptr ->
                val srv = records.filterIsInstance<Mdns.Record.Srv>().firstOrNull { it.name.equals(ptr.target, ignoreCase = true) }
                    ?: return@mapNotNull null
                val a = records.filterIsInstance<Mdns.Record.A>().firstOrNull { it.name.equals(srv.target, ignoreCase = true) }
                    ?: return@mapNotNull null
                val suffix = "." + ptr.name
                val name = if (ptr.target.endsWith(suffix, ignoreCase = true)) ptr.target.dropLast(suffix.length) else ptr.target
                DiscoveredBridge(name, a.address, srv.port)
            }
    }
}

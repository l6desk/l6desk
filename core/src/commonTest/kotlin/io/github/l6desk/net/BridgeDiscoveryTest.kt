package io.github.l6desk.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeDiscoveryTest {
    @Test
    fun theQueryIsAStandardOneQuestionPtrQuery() {
        val q = Mdns.query("_l6desk-bridge._tcp.local", Mdns.TYPE_PTR)
        val header = q.take(12).map { it.toInt() and 0xFF }
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0), header)
        val name = q.drop(12).dropLast(4)
        assertEquals(14, name.first().toInt(), "first label length (_l6desk-bridge)")
        assertEquals(0, name.last().toInt(), "root label")
        assertEquals(listOf(0, 12, 0, 1), q.takeLast(4).map { it.toInt() and 0xFF }, "type PTR, class IN")
    }

    @Test
    fun readsAnAvahiStyleReply() {
        val records = Mdns.parse(Replies.bridge("L6Desk Bridge", "l6desk-bridge.local", "192.168.1.166", 5704))
        assertEquals(
            listOf(DiscoveredBridge("L6Desk Bridge", "192.168.1.166", 5704)),
            BridgeDiscovery.bridgesIn(records),
        )
        val txt = records.filterIsInstance<Mdns.Record.Txt>().single()
        assertEquals(listOf("protocol=1"), txt.entries)
    }

    @Test
    fun bridgesNotYetUpdatedAreFoundUnderTheOldName() {
        val reply = Replies.bridge("L6max Bridge", "l6max-bridge.local", "192.168.1.166", 5704, service = BridgeDiscovery.OLD_SERVICE)
        assertEquals(listOf(DiscoveredBridge("L6max Bridge", "192.168.1.166", 5704)), BridgeDiscovery.bridgesIn(Mdns.parse(reply)))
    }

    @Test
    fun otherServicesQueriesAndIncompleteRepliesAreIgnored() {
        assertTrue(BridgeDiscovery.bridgesIn(Mdns.parse(Replies.bridge("x", "h.local", "10.0.0.1", 1, service = "_ssh._tcp.local"))).isEmpty())
        assertTrue(Mdns.parse(Mdns.query("_l6desk-bridge._tcp.local", Mdns.TYPE_PTR)).isEmpty(), "a query isn't a reply")
        assertTrue(BridgeDiscovery.bridgesIn(Mdns.parse(Replies.bridge("x", "h.local", null, 1))).isEmpty(), "no address yet")
    }

    @Test
    fun brokenPacketsAreRejectedNotCrashing() {
        val good = Replies.bridge("L6Desk Bridge", "l6desk-bridge.local", "192.168.1.166", 5704)
        assertFailsWith<Mdns.MalformedException> { Mdns.parse(good.copyOf(good.size - 7)) }
        // A name pointer that points at itself.
        val loop = byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0, 0xC0.toByte(), 12)
        assertFailsWith<Mdns.MalformedException> { Mdns.parse(loop) }
    }

    @Test
    fun findsBridgesAndDropsThemAfterThreeMissedRounds() = runTest {
        val net = FakeNetwork()
        net.online["L6Desk Bridge"] = Replies.bridge("L6Desk Bridge", "l6desk-bridge.local", "192.168.1.166", 5704)
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val discovery = BridgeDiscovery(scope, { net })
        discovery.start()
        runCurrent()
        assertEquals(listOf(DiscoveredBridge("L6Desk Bridge", "192.168.1.166", 5704)), discovery.found.value)

        net.online["Gig Box"] = Replies.bridge("Gig Box", "gigbox.local", "10.42.0.1", 5704)
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(listOf("Gig Box", "L6Desk Bridge"), discovery.found.value.map { it.name }, "sorted by name")

        net.online.remove("L6Desk Bridge") // switched off (after answering the current round)
        advanceTimeBy(12.seconds)
        runCurrent()
        assertEquals(2, discovery.found.value.size, "three missed rounds are allowed")
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(listOf("Gig Box"), discovery.found.value.map { it.name })

        net.garbage = true // a broken packet from someone else doesn't stop discovery
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(listOf("Gig Box"), discovery.found.value.map { it.name })

        discovery.stop()
        assertTrue(discovery.found.value.isEmpty())
    }

    @Test
    fun keepsTryingWhenTheNetworkIsntUpYet() = runTest {
        val net = FakeNetwork()
        net.online["L6Desk Bridge"] = Replies.bridge("L6Desk Bridge", "l6desk-bridge.local", "192.168.1.166", 5704)
        var attempts = 0
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val discovery = BridgeDiscovery(scope, { if (++attempts < 3) error("no Wi-Fi") else net })
        discovery.start()
        advanceTimeBy(7.seconds)
        runCurrent()
        assertEquals(1, discovery.found.value.size)
    }

    @Test
    fun theDesktopsOwnBridgeIsNeverOffered() = runTest {
        val net = FakeNetwork()
        net.online["Pi"] = Replies.bridge("Pi", "pi.local", "192.168.1.166", 5704)
        net.online["BandPC"] = Replies.bridge("BandPC", "bandpc-l6desk.local", "192.168.1.107", 5704)
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val noSockets = SocketConnector { _, _, _ -> error("not connecting in this test") }
        val transport = NetworkMidiTransport(scope, noSockets, "Desktop", discovery = BridgeDiscovery(scope, { net }))
        runCurrent()
        assertEquals(listOf("BandPC", "Pi"), transport.devices.value.map { it.id.removePrefix(NetworkMidiTransport.FOUND_PREFIX) })
        transport.setOwnBridge(setOf(BridgeAddress("192.168.1.107", 5704), BridgeAddress("127.0.0.1", 5704)))
        assertEquals(listOf("Pi"), transport.devices.value.map { it.id.removePrefix(NetworkMidiTransport.FOUND_PREFIX) })
        transport.setOwnBridge(emptySet()) // Bridge mode off: another PC's bridge of the same name is fine
        assertEquals(2, transport.devices.value.size)
    }

    /** Answers every query with the replies of the bridges currently online. */
    private class FakeNetwork : DatagramChannel {
        val online = linkedMapOf<String, ByteArray>()
        var garbage = false
        private val inbox = Channel<ByteArray>(Channel.UNLIMITED)

        override suspend fun send(packet: ByteArray, host: String, port: Int) {
            assertEquals(Mdns.GROUP to Mdns.PORT, host to port)
            if (garbage) inbox.trySend(byteArrayOf(1, 2, 3, 0x80.toByte()))
            online.values.forEach { inbox.trySend(it) }
        }

        override suspend fun receive(timeout: Duration): ByteArray? = withTimeoutOrNull(timeout) { inbox.receive() }

        override fun close() = Unit
    }
}

/** Builds mDNS replies shaped like Avahi's (PTR, TXT, SRV and A in the answers; compressed names). */
object Replies {
    fun bridge(instance: String, host: String, address: String?, port: Int, service: String = BridgeDiscovery.SERVICE): ByteArray {
        val out = mutableListOf<Byte>()
        val offsets = mutableMapOf<String, Int>()
        fun u16(v: Int) { out += (v shr 8).toByte(); out += v.toByte() }
        fun u32(v: Long) { u16((v shr 16).toInt()); u16(v.toInt()) }
        fun name(n: String) {
            var rest = n
            while (rest.isNotEmpty()) {
                offsets[rest]?.let { u16(0xC000 or it); return }
                offsets[rest] = out.size
                val label = rest.substringBefore('.')
                out += label.length.toByte()
                out += label.encodeToByteArray().toList()
                rest = rest.substringAfter('.', "")
            }
            out += 0
        }
        fun record(n: String, type: Int, body: () -> Unit) {
            name(n); u16(type); u16(0x0001); u32(10)
            val lengthAt = out.size
            u16(0)
            val start = out.size
            body()
            val length = out.size - start
            out[lengthAt] = (length shr 8).toByte()
            out[lengthAt + 1] = length.toByte()
        }
        val full = "$instance.$service"
        u16(0); u16(0x8400); u16(0); u16(if (address != null) 4 else 3); u16(0); u16(0)
        record(service, Mdns.TYPE_PTR) { name(full) }
        record(full, Mdns.TYPE_TXT) { val t = "protocol=1"; out += t.length.toByte(); out += t.encodeToByteArray().toList() }
        record(full, Mdns.TYPE_SRV) { u16(0); u16(0); u16(port); name(host) }
        if (address != null) record(host, Mdns.TYPE_A) { address.split('.').forEach { out += it.toInt().toByte() } }
        return out.toByteArray()
    }
}

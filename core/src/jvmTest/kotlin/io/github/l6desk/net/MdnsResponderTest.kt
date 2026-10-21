package io.github.l6desk.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The desktop's Bridge mode announcing itself, found by the apps' own discovery over this
 * computer's real network (sharing the mDNS port with Avahi). Skipped where there's no network
 * that carries multicast.
 */
class MdnsResponderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun theAppsDiscoveryFindsTheAnnouncedBridge() = runBlocking {
        if (MdnsResponder.interfaces().isEmpty()) return@runBlocking // no network here
        val name = "Test desk ${System.nanoTime() % 100000}"
        val responder = MdnsResponder(BridgeAnnouncement(name, 5799), scope)
        responder.start()
        try {
            val discovery = BridgeDiscovery(scope, JavaDatagramChannel::open, interval = 1.seconds)
            discovery.start()
            val found = withTimeoutOrNull(10.seconds) {
                while (discovery.found.value.none { it.name == name }) delay(100)
                discovery.found.value.first { it.name == name }
            }
            assertNotNull(found, "found ${discovery.found.value}")
            assertEquals(5799, found.port)
            val local = MdnsResponder.interfaces().flatMap { it.interfaceAddresses }.map { it.address.hostAddress }
            assertTrue(found.host in local, "${found.host} is one of this computer's addresses $local")
            discovery.stop()
        } finally {
            responder.stop()
        }
    }

    @Test
    fun bridgeModeIsAnnouncedOnceListeningAndSaysSoOrWhyNot() = runBlocking {
        val mixer = io.github.l6desk.midi.SimulatorTransport()
        val started = mutableListOf<BridgeAnnouncement>()
        var stopped = 0
        val bridge = AnnouncedBridge(BridgeServer(mixer, scope, "Studio PC"), "Studio PC", scope) { a ->
            object : AnnouncedBridge.Responder {
                override fun start() { started += a }
                override fun stop() { stopped++ }
            }
        }
        bridge.start(0)
        withTimeoutOrNull(5.seconds) { while (bridge.status.value.announce == null) delay(20) }
        assertEquals("Announced on the network as \"Studio PC\"", bridge.status.value.announce)
        assertEquals(bridge.status.value.port, started.single().port, "announced on the port it really listens on")
        bridge.stop()
        assertEquals(1, stopped, "goodbye when Bridge mode is turned off")
        withTimeoutOrNull(5.seconds) { while (bridge.status.value.announce != null) delay(20) }
        assertEquals(null, bridge.status.value.announce)

        val refused = AnnouncedBridge(BridgeServer(mixer, scope, "Studio PC"), "Studio PC", scope) {
            object : AnnouncedBridge.Responder {
                override fun start() = throw java.net.BindException("Address already in use")
                override fun stop() = Unit
            }
        }
        refused.start(0)
        withTimeoutOrNull(5.seconds) { while (refused.status.value.announce == null) delay(20) }
        assertEquals("Not announced on the network (Address already in use): add it by address", refused.status.value.announce)
        assertTrue(refused.status.value.running, "the bridge itself still works")
        refused.stop()
    }

    @Test
    fun answersOnlyQuestionsAboutTheBridge() {
        val a = BridgeAnnouncement("BandPC", 5704)
        assertEquals("bandpc-l6desk.local", a.host)
        fun q(name: String, type: Int) = Mdns.Query(7, listOf(Mdns.Question(name, type)))
        assertNotNull(a.answer(q("_l6desk-bridge._tcp.local", Mdns.TYPE_PTR), "192.168.1.20"))
        assertNotNull(a.answer(q("_L6DESK-BRIDGE._tcp.local", Mdns.TYPE_ANY), "192.168.1.20"))
        assertNotNull(a.answer(q("BandPC._l6desk-bridge._tcp.local", Mdns.TYPE_SRV), "192.168.1.20"))
        assertNotNull(a.answer(q("bandpc-l6desk.local", Mdns.TYPE_A), "192.168.1.20"))
        assertEquals(null, a.answer(q("_ssh._tcp.local", Mdns.TYPE_PTR), "192.168.1.20"))
        assertEquals(null, a.answer(q("_l6max-bridge._tcp.local", Mdns.TYPE_PTR), "192.168.1.20"), "the old name is only looked for, never announced")
        assertEquals(null, a.answer(q("bandpc.local", Mdns.TYPE_A), "192.168.1.20"), "never the computer's own name")
    }

    @Test
    fun aResponseIsReadBackByTheAppsParser() {
        val a = BridgeAnnouncement("Studio PC", 5704)
        val (answers, additional) = a.records("192.168.1.20", BridgeAnnouncement.LEGACY_TTL)
        val question = Mdns.Question(BridgeDiscovery.SERVICE, Mdns.TYPE_PTR)
        val packet = Mdns.response(answers, additional, id = 0x1234, questions = listOf(question))
        assertEquals(0x1234, ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF), "the legacy query's id comes back")
        assertEquals(listOf(DiscoveredBridge("Studio PC", "192.168.1.20", 5704)), BridgeDiscovery.bridgesIn(Mdns.parse(packet)))
        val txt = Mdns.parse(packet).filterIsInstance<Mdns.Record.Txt>().single()
        assertEquals(listOf("protocol=${BridgeProtocol.VERSION}", "kind=desktop"), txt.entries)
        assertEquals(null, Mdns.parseQuery(packet), "a response isn't a query")
        assertEquals(Mdns.Query(5, listOf(Mdns.Question(BridgeDiscovery.SERVICE, Mdns.TYPE_PTR))), Mdns.parseQuery(Mdns.query(BridgeDiscovery.SERVICE, Mdns.TYPE_PTR, id = 5)))
    }

    @Test
    fun subnets() {
        fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
        assertTrue(MdnsResponder.sameSubnet(ip("192.168.1.20"), ip("192.168.1.166"), 24))
        assertFalse(MdnsResponder.sameSubnet(ip("192.168.1.20"), ip("10.42.0.5"), 24))
        assertTrue(MdnsResponder.sameSubnet(ip("10.42.0.1"), ip("10.42.0.77"), 24))
    }
}

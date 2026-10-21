package io.github.l6desk.net

import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.SimulatorTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A bridge found on the network (faked mDNS) is a device the app can connect to over real TCP. */
class DiscoveryLoopbackTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    /** Replies to each query as a bridge on 127.0.0.1:[port] (changeable, like a new IP). */
    private class Announcer(@Volatile var port: Int) : DatagramChannel {
        private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        override suspend fun send(packet: ByteArray, host: String, port: Int) {
            inbox.trySend(Replies.bridge("Gig Box", "gigbox.local", "127.0.0.1", this.port))
        }
        override suspend fun receive(timeout: Duration) = withTimeoutOrNull(timeout) { inbox.receive() }
        override fun close() = Unit
    }

    private suspend fun eventually(what: String, check: () -> Boolean) {
        try {
            withTimeout(5.seconds) { while (!check()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for: $what")
        }
    }

    @Test
    fun aFoundBridgeIsListedOnceAndConnectsEvenAfterItsAddressChanges() = runBlocking {
        val server = BridgeServer(SimulatorTransport(), scope, "Gig Box", 100.milliseconds, 400.milliseconds).also { it.start(0) }
        eventually("bridge running") { server.status.value.running }
        val announcer = Announcer(port = 1) // nothing listens on port 1: the "old address"
        val discovery = BridgeDiscovery(scope, { announcer }, interval = 200.milliseconds)
        val client = NetworkMidiTransport(scope, JavaSocketConnector, "Test tablet", 100.milliseconds, 400.milliseconds, 100.milliseconds, discovery)

        eventually("bridge found") { client.devices.value.isNotEmpty() }
        val device = client.devices.value.single()
        assertEquals(NetworkMidiTransport.FOUND_PREFIX + "Gig Box", device.id)
        assertEquals("Gig Box (127.0.0.1:1)", device.name)

        client.open(device)
        eventually("can't reach the old address") { client.state.value is ConnectionState.Error }
        announcer.port = server.status.value.port // the bridge turns up at its new address
        eventually("connected at the new address") { client.state.value is ConnectionState.Connected }

        client.setBridges(listOf(BridgeAddress("127.0.0.1", server.status.value.port)))
        eventually("listed once, as entered") { client.devices.value.map { it.id } == listOf("bridge:127.0.0.1:${server.status.value.port}") }
        val entered = client.devices.value.single()
        assertEquals("Gig Box (127.0.0.1:${server.status.value.port})", entered.name, "named as found")
        assertEquals(true, entered.found)
        client.close()
    }
}

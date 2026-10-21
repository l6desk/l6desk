package io.github.l6desk.net

import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.MidiDeviceInfo
import io.github.l6desk.midi.MidiMessage
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.Control
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The whole bridge path over real TCP on localhost: client transport → BridgeServer → simulator
 * standing in for the L6max. Keepalive timings are shortened so the tests run quickly.
 */
class BridgeLoopbackTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keepalive = 100.milliseconds
    private val timeout = 400.milliseconds

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun server(mixer: SimulatorTransport, port: Int = 0, log: MutableList<MidiMessage>? = null) =
        BridgeServer(mixer, scope, "test-bridge", keepalive, timeout) { log?.add(it) }.also { it.start(port) }

    private fun client(name: String = "Test tablet") =
        NetworkMidiTransport(scope, JavaSocketConnector, name, keepalive, timeout, retryDelay = 150.milliseconds)

    private suspend fun eventually(what: String, check: () -> Boolean) {
        try {
            withTimeout(5.seconds) { while (!check()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for: $what")
        }
    }

    private suspend fun BridgeServer.port(): Int {
        eventually("bridge running") { status.value.running }
        return status.value.port
    }

    private suspend fun NetworkMidiTransport.connectTo(port: Int): MidiDeviceInfo {
        setBridges(listOf(BridgeAddress("127.0.0.1", port)))
        val device = devices.value.single()
        open(device)
        eventually("client connected") { state.value is ConnectionState.Connected }
        return device
    }

    private fun ch(n: Int, p: ChannelParam) = Control.Channel(n, p)

    @Test
    fun midiFlowsBothWays() = runBlocking<Unit> {
        val mixer = SimulatorTransport().also { it.open(SimulatorTransport.DEVICE) }
        val fromClient = mutableListOf<MidiMessage>()
        val bridge = server(mixer, log = fromClient)
        val tablet = client()
        tablet.connectTo(bridge.port())

        val name = (tablet.state.value as ConnectionState.Connected).device.name
        assertTrue("test-bridge" in name && "L6max Simulator" in name, name)
        eventually("bridge shows the client") { bridge.status.value.client?.startsWith("Test tablet") == true }

        // Tablet → bridge → mixer
        tablet.send(MidiMessage.ControlChange(1, 106, 127))
        eventually("CH8 mute reached the mixer") { mixer.valueOf(ch(8, ChannelParam.MUTE)) == 127 }
        assertEquals(listOf<MidiMessage>(MidiMessage.ControlChange(1, 106, 127)), fromClient.toList())

        // Mixer hardware move → bridge → tablet
        val heard = mutableListOf<MidiMessage>()
        scope.launch { tablet.incoming.toList(heard) }
        delay(50)
        mixer.moveHardwareControl(ch(2, ChannelParam.PAN), 0)
        eventually("pan move reached the tablet") { MidiMessage.ControlChange(1, 74, 0) in heard }
    }

    @Test
    fun aSecondClientIsRefusedUntilTheFirstLeaves() = runBlocking<Unit> {
        val mixer = SimulatorTransport().also { it.open(SimulatorTransport.DEVICE) }
        val bridge = server(mixer)
        val port = bridge.port()
        val first = client("First")
        first.connectTo(port)

        val second = client("Second")
        second.setBridges(listOf(BridgeAddress("127.0.0.1", port)))
        second.open(second.devices.value.single())
        eventually("second refused") { (second.state.value as? ConnectionState.Error)?.reason?.contains("busy") == true }
        assertIs<ConnectionState.Connected>(first.state.value, "the first client is unaffected")

        first.close()
        eventually("second gets in after the first leaves") { second.state.value is ConnectionState.Connected }
        assertTrue(bridge.status.value.client?.startsWith("Second") == true)
    }

    @Test
    fun linkLossIsReportedQuicklyAndTheClientReconnects() = runBlocking<Unit> {
        val mixer = SimulatorTransport().also { it.open(SimulatorTransport.DEVICE) }
        val bridge = server(mixer)
        val port = bridge.port()
        val tablet = client()
        tablet.connectTo(port)

        bridge.stop()
        eventually("link lost reported") { (tablet.state.value as? ConnectionState.Error)?.reason?.contains("lost") == true }

        server(mixer, port) // the bridge comes back on the same port
        eventually("reconnected") { tablet.state.value is ConnectionState.Connected }
        tablet.send(MidiMessage.ControlChange(1, 81, 42))
        eventually("works again") { mixer.valueOf(ch(1, ChannelParam.LEVEL)) == 42 }
    }

    @Test
    fun theTabletIsToldWhenTheMixerIsUnpluggedAtTheBridge() = runBlocking<Unit> {
        val mixer = SimulatorTransport().also { it.open(SimulatorTransport.DEVICE) }
        val bridge = server(mixer)
        val tablet = client()
        tablet.connectTo(bridge.port())

        mixer.close()
        eventually("no mixer shown") { (tablet.state.value as? ConnectionState.Connected)?.device?.name?.contains("no mixer") == true }
        mixer.open(SimulatorTransport.DEVICE)
        eventually("mixer back") { (tablet.state.value as? ConnectionState.Connected)?.device?.name?.contains("L6max Simulator") == true }
    }

    @Test
    fun aSilentClientIsDroppedAndItsSlotFreed() = runBlocking<Unit> {
        val mixer = SimulatorTransport().also { it.open(SimulatorTransport.DEVICE) }
        val bridge = server(mixer)
        val port = bridge.port()
        // A client that says hello and then never sends anything again (e.g. Wi-Fi dropped).
        val raw = JavaSocketConnector.connect("127.0.0.1", port, 1.seconds)
        raw.write(FrameEncoder.encode(Frame.Hello(BridgeProtocol.VERSION, "Vanished")))
        eventually("slot taken") { bridge.status.value.client != null }
        eventually("slot freed after the timeout") { bridge.status.value.client == null }
        raw.close()
        client().connectTo(port) // someone else can now connect
    }

    @Test
    fun aVersion1AppIsWelcomedAsVersion1AndGetsNoPlayerFrames() = runBlocking<Unit> {
        val mixer = SimulatorTransport().also { it.open(SimulatorTransport.DEVICE) }
        val player = object : io.github.l6desk.player.MusicPlayer {
            override val tracks = kotlinx.coroutines.flow.MutableStateFlow(listOf(io.github.l6desk.player.Track("a.wav", "a", 1000)))
            override val state = kotlinx.coroutines.flow.MutableStateFlow(io.github.l6desk.player.PlayerState())
            override fun handle(command: io.github.l6desk.player.PlayerCommand) = Unit
        }
        val bridge = BridgeServer(mixer, scope, "test-bridge", keepalive, timeout, player).also { it.start(0) }
        val port = bridge.port()
        // An app from before the player (e.g. 0.11.0): protocol 1, which can't read player frames.
        val raw = JavaSocketConnector.connect("127.0.0.1", port, 1.seconds)
        raw.write(FrameEncoder.encode(Frame.Hello(1, "Old app")))
        val reader = FrameReader()
        val frames = mutableListOf<Frame>()
        val buffer = ByteArray(4096)
        // Listen for a while: player frames would follow the Welcome straight away.
        kotlinx.coroutines.withTimeoutOrNull(800.milliseconds) {
            while (true) {
                val n = raw.read(buffer)
                if (n < 0) break
                frames += reader.feed(buffer, n)
            }
        }
        raw.close()
        assertTrue(frames.any { it is Frame.Status }, "the session got going: $frames")
        assertEquals(1, frames.filterIsInstance<Frame.Welcome>().single().version)
        assertTrue(frames.none { it is Frame.Tracks || it is Frame.Playback }, "no player frames for a v1 app: $frames")
    }

    @Test
    fun aBridgeAllowedToPowerOffDoesSoWhenAskedAndAcknowledgesIt() = runBlocking<Unit> {
        val mixer = SimulatorTransport().also { it.open(SimulatorTransport.DEVICE) }
        var poweredOff = 0
        val bridge = BridgeServer(mixer, scope, "test-bridge", keepalive, timeout, powerOff = { poweredOff++ }).also { it.start(0) }
        val tablet = client()
        tablet.connectTo(bridge.port())
        eventually("the bridge offers to power off") { tablet.canPowerOff.value }
        assertEquals(0, poweredOff, "only when asked")
        assertTrue(tablet.powerOffBridge(), "acknowledged")
        assertEquals(1, poweredOff)
        tablet.close()
        assertEquals(false, tablet.canPowerOff.value)
    }

    @Test
    fun aBridgeWithoutPowerOffNeverOffersItAndIgnoresTheRequest() = runBlocking<Unit> {
        // E.g. the desktop app's Bridge mode: a tablet must never switch the PC off.
        val mixer = SimulatorTransport().also { it.open(SimulatorTransport.DEVICE) }
        val tablet = client()
        tablet.connectTo(server(mixer).port())
        delay(300)
        assertEquals(false, tablet.canPowerOff.value)
        assertEquals(false, tablet.powerOffBridge(500.milliseconds))
        assertIs<ConnectionState.Connected>(tablet.state.value, "and the session carries on")
    }

    @Test
    fun aVersion3AppCantPowerTheBridgeOff() = runBlocking<Unit> {
        val mixer = SimulatorTransport().also { it.open(SimulatorTransport.DEVICE) }
        var poweredOff = 0
        val bridge = BridgeServer(mixer, scope, "test-bridge", keepalive, timeout, powerOff = { poweredOff++ }).also { it.start(0) }
        val raw = JavaSocketConnector.connect("127.0.0.1", bridge.port(), 1.seconds)
        raw.write(FrameEncoder.encode(Frame.Hello(3, "0.13 app")))
        raw.write(FrameEncoder.encode(Frame.PowerOff))
        val reader = FrameReader()
        val frames = mutableListOf<Frame>()
        val buffer = ByteArray(4096)
        kotlinx.coroutines.withTimeoutOrNull(600.milliseconds) {
            while (true) {
                val n = raw.read(buffer)
                if (n < 0) break
                frames += reader.feed(buffer, n)
            }
        }
        raw.close()
        assertEquals(3, frames.filterIsInstance<Frame.Welcome>().single().version)
        assertTrue(frames.none { it == Frame.CanPowerOff || it == Frame.PowerOff }, "not offered to v3: $frames")
        assertEquals(0, poweredOff)
    }

    @Test
    fun aV5BridgeDescribesItselfAfterWelcomeWhenItChangesAndWhenAsked() = runBlocking<Unit> {
        val mixer = SimulatorTransport().also { it.open(SimulatorTransport.DEVICE) }
        var temperature = 45.0
        val warnings = kotlinx.coroutines.flow.MutableStateFlow(emptyList<String>())
        val bridge = BridgeServer(
            mixer, scope, "test-bridge", keepalive, timeout,
            info = { BridgeInfo(BridgeInfo.KIND_PI, "0.14.0", temperatureC = temperature) }, infoUpdates = warnings,
        ).also { it.start(0) }
        val tablet = client()
        tablet.connectTo(bridge.port())
        eventually("info after welcome") { tablet.bridgeInfo.value?.temperatureC == 45.0 }
        assertEquals(BridgeInfo.KIND_PI, tablet.bridgeInfo.value?.kind)

        temperature = 50.0
        assertEquals(50.0, tablet.refreshBridgeInfo()?.temperatureC, "fresh when asked")

        temperature = 82.0
        warnings.value = listOf("hot")
        eventually("sent when a warning appears") { tablet.bridgeInfo.value?.temperatureC == 82.0 }

        tablet.close()
        assertEquals(null, tablet.bridgeInfo.value)
    }

    @Test
    fun aBridgeWithoutInfoOrAnOlderAppGetsNone() = runBlocking<Unit> {
        val mixer = SimulatorTransport().also { it.open(SimulatorTransport.DEVICE) }
        val tablet = client()
        tablet.connectTo(server(mixer).port())
        assertEquals(null, tablet.refreshBridgeInfo(300.milliseconds), "no info without an info source")

        // A 0.13 app (v4) is never sent BridgeInfo, even by a bridge that has it.
        val bridge = BridgeServer(mixer, scope, "pi", keepalive, timeout, info = { BridgeInfo(BridgeInfo.KIND_PI, "0.14.0") }).also { it.start(0) }
        val raw = JavaSocketConnector.connect("127.0.0.1", bridge.port(), 1.seconds)
        raw.write(FrameEncoder.encode(Frame.Hello(4, "0.13 app")))
        raw.write(FrameEncoder.encode(Frame.BridgeInfoRequest))
        val reader = FrameReader()
        val frames = mutableListOf<Frame>()
        val buffer = ByteArray(4096)
        kotlinx.coroutines.withTimeoutOrNull(600.milliseconds) {
            while (true) {
                val n = raw.read(buffer)
                if (n < 0) break
                frames += reader.feed(buffer, n)
            }
        }
        raw.close()
        assertEquals(4, frames.filterIsInstance<Frame.Welcome>().single().version)
        assertTrue(frames.none { it is Frame.BridgeInfo }, "not sent to v4: $frames")
    }

    @Test
    fun aDeskThatIsItselfOnABridgeServesNoTablets() = runBlocking<Unit> {
        // Pi (simulator) ← desktop desk (through the Pi) ← tablet: the desktop's Bridge mode has no
        // mixer of its own to offer, so the tablet is told to use the Pi directly.
        val mixer = SimulatorTransport().also { it.open(SimulatorTransport.DEVICE) }
        val pi = server(mixer)
        val desk = client("Desktop")
        desk.connectTo(pi.port())
        val desktopBridge = BridgeServer(desk, scope, "BandPC", keepalive, timeout).also { it.start(0) }
        val tablet = client("Tablet")
        tablet.setBridges(listOf(BridgeAddress("127.0.0.1", desktopBridge.port())))
        tablet.open(tablet.devices.value.single())
        eventually("refused") { (tablet.state.value as? ConnectionState.Error)?.reason?.contains("connected through a bridge itself") == true }
        assertEquals(null, desktopBridge.status.value.client)
    }

    @Test
    fun aDeskConnectedToItsOwnBridgeModeIsRefusedNotLooped() = runBlocking<Unit> {
        // The desktop app picking its own Bridge mode (e.g. its address typed in): every message
        // would go round and round. It's refused instead, every time it tries.
        val desk = client("Desktop")
        val forwarded = java.util.concurrent.atomic.AtomicInteger()
        val own = BridgeServer(desk, scope, "BandPC", keepalive, timeout) { forwarded.incrementAndGet() }.also { it.start(0) }
        desk.setBridges(listOf(BridgeAddress("127.0.0.1", own.port())))
        desk.open(desk.devices.value.single())
        eventually("refused") { (desk.state.value as? ConnectionState.Error)?.reason?.contains("connected through a bridge itself") == true }
        delay(2_500) // a reconnect attempt or two
        assertIs<ConnectionState.Error>(desk.state.value, "still refused after retrying")
        desk.send(MidiMessage.ControlChange(1, 81, 100))
        delay(200)
        assertEquals(0, forwarded.get(), "nothing went round")
        desk.close()
    }

    private fun pinBridge(pin: String?) =
        BridgeServer(SimulatorTransport().also { runBlocking { it.open(SimulatorTransport.DEVICE) } }, scope, "pin-bridge", keepalive, timeout, pin = pin).also { it.start(0) }

    @Test
    fun aBridgeWithAPinLetsInAnAppThatKnowsIt() = runBlocking<Unit> {
        val bridge = pinBridge("2468")
        val tablet = client()
        tablet.setBridges(listOf(BridgeAddress("127.0.0.1", bridge.port())))
        val device = tablet.devices.value.single()
        tablet.setPins(mapOf(device.id to "2468"))
        tablet.open(device)
        eventually("connected with the saved PIN") { tablet.state.value is ConnectionState.Connected }
        assertEquals(null, tablet.pinRequest.value)
    }

    @Test
    fun withoutAPinTheAppAsksThenCarriesOn() = runBlocking<Unit> {
        val bridge = pinBridge("2468")
        val tablet = client()
        tablet.setBridges(listOf(BridgeAddress("127.0.0.1", bridge.port())))
        val device = tablet.devices.value.single()
        tablet.open(device)
        eventually("asked for the PIN") { tablet.pinRequest.value == PinRequest(device, wrong = false) }
        delay(500)
        assertEquals(null, bridge.status.value.client, "not let in meanwhile")

        tablet.setPin(device.id, "1111")
        eventually("told it was wrong") { tablet.pinRequest.value == PinRequest(device, wrong = true) }
        assertTrue((tablet.state.value as ConnectionState.Error).reason.startsWith("Wrong PIN"))

        tablet.setPin(device.id, "2468")
        eventually("connected") { tablet.state.value is ConnectionState.Connected }
        assertEquals(null, tablet.pinRequest.value)
    }

    @Test
    fun aPinBridgeAsksOlderAppsToUpdateAndAPinCanBeSetWhileRunning() = runBlocking<Unit> {
        val bridge = pinBridge(null)
        val tablet = client()
        tablet.connectTo(bridge.port()) // no PIN: straight in
        tablet.close()
        bridge.setPin("97531")
        val raw = JavaSocketConnector.connect("127.0.0.1", bridge.port(), 1.seconds)
        raw.write(FrameEncoder.encode(Frame.Hello(5, "0.15 app")))
        val frames = readFor(raw, 5_000) { f -> f.any { it is Frame.Busy } }
        assertEquals("This bridge has a PIN: update the app to 0.16 or newer", frames.filterIsInstance<Frame.Busy>().single().reason)
        assertTrue(frames.none { it is Frame.Welcome })
        bridge.setPin("12") // not a valid PIN (4 to 8 digits): no PIN
        tablet.open(tablet.devices.value.single())
        eventually("in again") { tablet.state.value is ConnectionState.Connected }
    }

    @Test
    fun theRightAnswerIsAnHmacAndTooManyWrongOnesLockTheAddressOut() = runBlocking<Unit> {
        val bridge = pinBridge("2468")
        repeat(5) {
            val raw = JavaSocketConnector.connect("127.0.0.1", bridge.port(), 1.seconds)
            raw.write(FrameEncoder.encode(Frame.Hello(BridgeProtocol.VERSION, "guesser")))
            val challenge = readFor(raw, 5_000) { f -> f.any { it is Frame.PinRequired } }.filterIsInstance<Frame.PinRequired>().single()
            assertEquals(16, challenge.nonce.size)
            raw.write(FrameEncoder.encode(Frame.PinAnswer(BridgePin.answer("000$it", challenge.nonce))))
            assertEquals(BridgeProtocol.WRONG_PIN, readFor(raw, 5_000) { f -> f.any { it is Frame.Busy } }.filterIsInstance<Frame.Busy>().single().reason)
        }
        val raw = JavaSocketConnector.connect("127.0.0.1", bridge.port(), 1.seconds)
        raw.write(FrameEncoder.encode(Frame.Hello(BridgeProtocol.VERSION, "guesser")))
        assertEquals("Too many wrong PINs: try again in a minute", readFor(raw, 5_000) { f -> f.any { it is Frame.Busy } }.filterIsInstance<Frame.Busy>().single().reason)
        assertFalse(BridgePin.answer("2468", ByteArray(16)).decodeToString().contains("2468"), "the PIN never goes over the network")
    }

    /**
     * Frames from [raw] until [until] is satisfied, it closes, or [ms] milliseconds pass (generous:
     * the first secure random numbers can take a moment, e.g. on Windows).
     */
    private suspend fun readFor(raw: ByteSocket, ms: Long, until: (List<Frame>) -> Boolean = { false }): List<Frame> {
        val reader = FrameReader()
        val frames = mutableListOf<Frame>()
        val buffer = ByteArray(4096)
        kotlinx.coroutines.withTimeoutOrNull(ms) {
            while (!until(frames)) {
                val n = raw.read(buffer)
                if (n < 0) break
                frames += reader.feed(buffer, n)
            }
        }
        return frames
    }

    @Test
    fun anOlderBridgeIsSpokenToInTheVersionItNames() = runBlocking<Unit> {
        // A 0.13 bridge (protocol 3) refuses a newer app's Hello, naming its own version.
        val server = java.net.ServerSocket(0)
        val hellos = java.util.Collections.synchronizedList(mutableListOf<Int>())
        // runCatching: closing the sockets at the end must not leave exceptions for later tests.
        scope.launch(Dispatchers.IO) {
            while (true) {
                val socket = JavaByteSocket(runCatching { server.accept() }.getOrNull() ?: break)
                launch { runCatching {
                    val reader = FrameReader()
                    val buffer = ByteArray(1024)
                    var hello: Frame.Hello? = null
                    while (hello == null) {
                        val n = socket.read(buffer)
                        if (n < 0) return@runCatching
                        hello = reader.feed(buffer, n).filterIsInstance<Frame.Hello>().firstOrNull()
                    }
                    hellos += hello.version
                    if (hello.version > 3) {
                        socket.write(FrameEncoder.encode(Frame.Busy("Protocol v${hello.version} isn't supported; this bridge speaks v3")))
                        socket.close()
                    } else {
                        socket.write(FrameEncoder.encode(Frame.Welcome(3, "old-pi", MixerStatus(true, "L6max"))))
                        while (socket.read(buffer) > 0) socket.write(FrameEncoder.encode(Frame.Ping))
                    }
                } }
            }
        }
        val tablet = client()
        tablet.connectTo(server.localPort)
        assertEquals(listOf(BridgeProtocol.VERSION, 3), hellos.toList())
        assertEquals(false, tablet.canPowerOff.value, "nothing a v3 bridge can't do")
        tablet.close()
        server.close()
    }

    @Test
    fun stoppingTheBridgeIsNotReportedAsAnError() = runBlocking<Unit> {
        val bridge = server(SimulatorTransport())
        bridge.port()
        bridge.stop()
        delay(200)
        assertEquals(null, bridge.status.value.error)
        assertEquals(false, bridge.status.value.running)
    }

    @Test
    fun aPortInUseIsReportedAsAnError() = runBlocking<Unit> {
        val first = server(SimulatorTransport())
        val port = first.port()
        val second = server(SimulatorTransport(), port)
        eventually("error reported") { second.status.value.error != null }
    }

    @Test
    fun bridgeAddressParsing() {
        assertEquals(BridgeAddress("192.168.1.20", 5704), BridgeAddress.parse("192.168.1.20"))
        assertEquals(BridgeAddress("192.168.1.20", 5705), BridgeAddress.parse(" 192.168.1.20:5705 "))
        assertEquals(BridgeAddress("pi.local", 5704), BridgeAddress.parse("pi.local"))
        assertEquals(null, BridgeAddress.parse("192.168.1.20:99999"))
        assertEquals(null, BridgeAddress.parse("bad host"))
        assertEquals(null, BridgeAddress.parse(""))
        assertEquals("192.168.1.20", BridgeAddress("192.168.1.20").toString())
        assertEquals("bridge:192.168.1.20:5704", BridgeAddress("192.168.1.20").id)
    }
}

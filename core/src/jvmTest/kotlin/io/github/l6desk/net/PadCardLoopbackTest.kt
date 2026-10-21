package io.github.l6desk.net

import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.padcard.BridgePadCardSupport
import io.github.l6desk.padcard.PadCardHost
import io.github.l6desk.padcard.PadCardState
import io.github.l6desk.padcard.PadCommand
import io.github.l6desk.padcard.PadResult
import io.github.l6desk.padcard.PadSound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** PAD SOUNDS through the bridge over real TCP on localhost (protocol v7). */
class PadCardLoopbackTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private suspend fun eventually(what: String, check: () -> Boolean) {
        try {
            withTimeout(10.seconds) { while (!check()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for: $what")
        }
    }

    private class FakeCard : PadCardHost {
        override val card = MutableStateFlow<PadCardState?>(null)
        val commands: MutableList<PadCommand> = Collections.synchronizedList(mutableListOf())
        override fun handle(command: PadCommand): PadResult {
            commands += command
            Thread.sleep(300) // copying to a card takes a while
            return when (command) {
                is PadCommand.Set -> PadResult(command.id, "Set pad ${command.pad}")
                is PadCommand.Clear -> PadResult(command.id, "No card", error = true)
                is PadCommand.Eject -> PadResult(command.id, "Ejected")
            }
        }
    }

    private suspend fun connected(card: PadCardHost?): NetworkMidiTransport {
        val mixer = SimulatorTransport().apply { open(SimulatorTransport.DEVICE) }
        val bridge = BridgeServer(mixer, scope, "test-pi", 100.milliseconds, 400.milliseconds, padCard = card).also { it.start(0) }
        eventually("bridge running") { bridge.status.value.running }
        val tablet = NetworkMidiTransport(scope, JavaSocketConnector, "Test tablet", 100.milliseconds, 400.milliseconds, retryDelay = 150.milliseconds)
        tablet.setBridges(listOf(BridgeAddress("127.0.0.1", bridge.status.value.port)))
        tablet.open(tablet.devices.value.single())
        eventually("connected") { tablet.state.value is ConnectionState.Connected }
        return tablet
    }

    @Test
    fun theCardAtTheBridgeIsShownAndChanged() = runBlocking<Unit> {
        val card = FakeCard()
        val tablet = connected(card)
        eventually("offered") { tablet.padCardOffered.value }
        assertNull(tablet.padCard.value, "no card until the mixer is in file transfer mode")
        val state = PadCardState("at the Pi", listOf(PadSound(1, "Fanfare.wav", 500)) + (2..4).map { PadSound(it, null) })
        card.card.value = state
        eventually("card shown") { tablet.padCard.value == state }

        val support = BridgePadCardSupport(tablet, scope)
        assertEquals("Set pad 3", support.chooseTrack(3, io.github.l6desk.player.Track("Openers/01 Fanfare.wav", "Fanfare", 500)))
        assertEquals(PadCommand.Set(1, 3, "Openers/01 Fanfare.wav"), card.commands.single())
        assertEquals("No card", assertFailsWith<IllegalStateException> { support.clear(2) }.message)
        assertEquals("Ejected", support.eject())
    }

    @Test
    fun aBridgeWithoutTheCardDoesntOfferIt() = runBlocking<Unit> {
        val tablet = connected(null)
        delay(500)
        assertFalse(tablet.padCardOffered.value)
        assertEquals(true, tablet.padCommand { PadCommand.Eject(it) }.error)
    }

    @Test
    fun aLostLinkEndsTheWait() = runBlocking<Unit> {
        val card = object : PadCardHost {
            override val card = MutableStateFlow<PadCardState?>(null)
            override fun handle(command: PadCommand): PadResult {
                Thread.sleep(60_000)
                error("never")
            }
        }
        val tablet = connected(card)
        eventually("offered") { tablet.padCardOffered.value }
        val result = scope.async { tablet.padCommand { PadCommand.Eject(it) } }
        delay(200)
        tablet.close()
        assertEquals(true, withTimeout(5.seconds) { result.await() }.error)
    }
}

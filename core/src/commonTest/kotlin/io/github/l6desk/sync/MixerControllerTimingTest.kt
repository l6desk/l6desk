package io.github.l6desk.sync

import io.github.l6desk.midi.MidiMessage
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.Control
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Throttling and echo suppression, on the test scheduler's virtual clock (no real waiting). */
@OptIn(ExperimentalCoroutinesApi::class)
class MixerControllerTimingTest {
    private val ch1Level = Control.Channel(1, ChannelParam.LEVEL)
    private val ch2Level = Control.Channel(2, ChannelParam.LEVEL)
    private val ch1Pan = Control.Channel(1, ChannelParam.PAN)

    private suspend fun TestScope.rig(echo: Boolean = false): Pair<SimulatorTransport, MixerController> {
        val sim = SimulatorTransport(echo = echo)
        val c = MixerController(sim, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        sim.open(SimulatorTransport.DEVICE)
        c.pushAll()
        advanceTimeBy(1_000)
        sim.received.clear()
        return sim to c
    }

    private fun SimulatorTransport.sentFor(cc: Int) =
        received.filterIsInstance<MidiMessage.ControlChange>().filter { it.controller == cc }.map { it.value }

    /** Drags CH1's fader from 0 to 99 over ~1 s: an update every 10 ms, as a touchscreen might. */
    private fun TestScope.drag(c: MixerController, control: Control.Channel = ch1Level, values: IntProgression = 0..99) {
        for (v in values) {
            c.setParam(control.channel, control.param, v)
            advanceTimeBy(10)
        }
    }

    @Test
    fun aDragIsThrottledToAbout50MessagesPerSecond() = runTest {
        val (sim, c) = rig()
        c.setTouching(ch1Level, true)
        drag(c)
        val sent = sim.sentFor(81)
        assertTrue(sent.size in 45..52, "sent ${sent.size} messages for 100 updates in 1 s")
        assertEquals(sent, sent.sorted(), "values arrive in order")
    }

    @Test
    fun theFirstChangeGoesOutImmediately() = runTest {
        val (sim, c) = rig()
        c.setParam(1, ChannelParam.LEVEL, 10)
        assertEquals(listOf(10), sim.sentFor(81))
    }

    @Test
    fun theFinalValueAlwaysArrivesWhenTheWindowCloses() = runTest {
        val (sim, c) = rig()
        c.setParam(1, ChannelParam.LEVEL, 10)
        c.setParam(1, ChannelParam.LEVEL, 11)
        c.setParam(1, ChannelParam.LEVEL, 12)
        assertEquals(listOf(10), sim.sentFor(81), "coalesced inside the window")
        advanceTimeBy(25)
        assertEquals(listOf(10, 12), sim.sentFor(81), "only the latest value is sent")
        assertEquals(12, sim.valueOf(ch1Level))
    }

    @Test
    fun releasingAControlSendsItsPendingValueAtOnce() = runTest {
        val (sim, c) = rig()
        c.setTouching(ch1Level, true)
        c.setParam(1, ChannelParam.LEVEL, 10)
        c.setParam(1, ChannelParam.LEVEL, 20)
        advanceTimeBy(5)
        c.setTouching(ch1Level, false)
        assertEquals(listOf(10, 20), sim.sentFor(81), "sent on release, 5 ms in, not at 20 ms")
    }

    @Test
    fun aValueThatReturnsWithinTheWindowIsNotResent() = runTest {
        val (sim, c) = rig()
        c.setParam(1, ChannelParam.LEVEL, 10)
        c.setParam(1, ChannelParam.LEVEL, 11)
        c.setParam(1, ChannelParam.LEVEL, 10)
        advanceTimeBy(50)
        assertEquals(listOf(10), sim.sentFor(81))
    }

    @Test
    fun eachControlHasItsOwnBudget() = runTest {
        val (sim, c) = rig()
        c.setParam(1, ChannelParam.LEVEL, 10)
        c.setParam(2, ChannelParam.LEVEL, 10)
        c.setParam(1, ChannelParam.PAN, 0)
        assertEquals(listOf(10), sim.sentFor(81))
        assertEquals(listOf(10), sim.sentFor(82))
        assertEquals(listOf(0), sim.sentFor(73))
    }

    @Test
    fun masterMuteIsNeverDelayedByAFaderMove() = runTest {
        val (sim, c) = rig()
        c.setParam(1, ChannelParam.LEVEL, 10) // CH1 level is now inside its window
        c.setMasterMute(true)
        assertTrue((93..95).all { sim.sentFor(it) == listOf(127) }, "mutes are separate controls and go out at once")
    }

    @Test
    fun pushAllIsNotHeldBackByOpenWindows() = runTest {
        val (sim, c) = rig()
        c.setParam(1, ChannelParam.LEVEL, 10)
        sim.received.clear()
        c.pushAll()
        assertEquals(MixerController.ALWAYS_SENT_COUNT, sim.received.size)
    }

    @Test
    fun echoesOfADragDoNotPullTheFaderBack() = runTest {
        val (sim, c) = rig(echo = true)
        c.setTouching(ch1Level, true)
        drag(c, values = 0..40)
        c.setTouching(ch1Level, false)
        assertEquals(40, c.state.value.channel(1).level)
        // A late echo of an earlier value, still inside the echo window, is ignored too.
        sim.moveHardwareControl(ch1Level, 38)
        assertEquals(40, c.state.value.channel(1).level)
    }

    @Test
    fun theSameValueFromTheHardwareIsAcceptedOnceTheEchoWindowHasPassed() = runTest {
        val (sim, c) = rig()
        c.setParam(1, ChannelParam.PAN, 10)
        advanceTimeBy(100)
        sim.moveHardwareControl(ch1Pan, 10) // looks like an echo
        c.setParam(1, ChannelParam.PAN, 20)
        advanceTimeBy(100)
        sim.moveHardwareControl(ch1Pan, 10) // 200 ms after it was sent: a real move
        assertEquals(10, c.state.value.channel(1).pan)
    }

    @Test
    fun differentValuesFromTheHardwareAreNeverTakenForEchoes() = runTest {
        val (sim, c) = rig()
        c.setParam(1, ChannelParam.PAN, 10)
        sim.moveHardwareControl(ch1Pan, 90)
        assertEquals(90, c.state.value.channel(1).pan)
    }

    @Test
    fun theHardwareCannotMoveAControlTheUserIsTouching() = runTest {
        val (sim, c) = rig()
        c.setTouching(ch2Level, true)
        c.setParam(2, ChannelParam.LEVEL, 70)
        sim.moveHardwareControl(ch2Level, 5)
        assertEquals(70, c.state.value.channel(2).level, "the finger wins")
        c.setTouching(ch2Level, false)
        advanceTimeBy(500)
        sim.moveHardwareControl(ch2Level, 5)
        assertEquals(5, c.state.value.channel(2).level, "after release the hardware can move it")
    }

    @Test
    fun disconnectingDropsPendingValues() = runTest {
        val (sim, c) = rig()
        c.setParam(1, ChannelParam.LEVEL, 10)
        c.setParam(1, ChannelParam.LEVEL, 20) // pending
        sim.close()
        advanceTimeBy(50)
        sim.open(SimulatorTransport.DEVICE)
        assertEquals(listOf(10), sim.sentFor(81), "nothing leaked out after the disconnect")
        assertTrue(c.needsPush.value)
        c.pushAll()
        assertEquals(20, sim.valueOf(ch1Level))
        assertFalse(c.needsPush.value)
    }
}

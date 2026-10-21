package io.github.l6desk.sync

import io.github.l6desk.midi.MidiMessage
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.Control
import io.github.l6desk.model.L6maxLayout
import io.github.l6desk.model.MixerState
import io.github.l6desk.model.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MixerControllerTest {
    private fun ch(n: Int, p: ChannelParam) = Control.Channel(n, p)

    private suspend fun TestScope.rig(): Pair<SimulatorTransport, MixerController> {
        val sim = SimulatorTransport()
        val controller = MixerController(sim, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        sim.open(SimulatorTransport.DEVICE)
        return sim to controller
    }

    private fun SimulatorTransport.ccs() = received.filterIsInstance<MidiMessage.ControlChange>()

    /** Lets every throttle and echo window close (virtual time), like a pause between actions. */
    private fun TestScope.settle() = advanceTimeBy(1_000)

    @Test
    fun connectingFlagsThatTheDeskNeedsPushingAndSendsNothing() = runTest {
        val (sim, c) = rig()
        assertTrue(c.needsPush.value)
        assertTrue(sim.received.isEmpty())
    }

    @Test
    fun pushAllSendsEveryDeskControl() = runTest {
        val (sim, c) = rig()
        c.pushAll()
        assertEquals(MixerController.ALWAYS_SENT_COUNT, sim.ccs().size)
        assertEquals(8 * 7 + 2, sim.ccs().size)
        assertFalse(c.needsPush.value)
        assertEquals(100, sim.valueOf(ch(1, ChannelParam.LEVEL)))
        assertEquals(0, sim.valueOf(ch(5, ChannelParam.MONO_X2)))
        assertEquals(null, sim.valueOf(ch(1, ChannelParam.AUX1)), "sends are never touched")
    }

    @Test
    fun onlyChangesAreSent() = runTest {
        val (sim, c) = rig()
        c.pushAll()
        settle()
        sim.received.clear()
        c.setParam(3, ChannelParam.PAN, 10)
        c.setParam(3, ChannelParam.PAN, 10)
        assertEquals(listOf(MidiMessage.ControlChange(1, 75, 10)), sim.ccs())
    }

    @Test
    fun soloAndMasterMuteSendMutes() = runTest {
        val (sim, c) = rig()
        c.pushAll()
        settle()
        sim.received.clear()
        c.setSolo(2, true)
        assertEquals(7, sim.ccs().size)
        assertEquals(0, sim.valueOf(ch(2, ChannelParam.MUTE)))
        assertEquals(127, sim.valueOf(ch(1, ChannelParam.MUTE)))
        settle()
        c.clearSolo()
        settle()
        c.setMasterMute(true)
        assertTrue((1..8).all { sim.valueOf(ch(it, ChannelParam.MUTE)) == 127 })
        settle()
        c.setMasterMute(false)
        assertTrue((1..8).all { sim.valueOf(ch(it, ChannelParam.MUTE)) == 0 })
    }

    @Test
    fun masterScalesSentLevels() = runTest {
        val (sim, c) = rig()
        c.pushAll()
        settle()
        c.setMaster(38)
        assertEquals(50, sim.valueOf(ch(1, ChannelParam.LEVEL)))
        settle()
        c.setMaster(127)
        assertEquals(127, sim.valueOf(ch(1, ChannelParam.LEVEL)), "boosted past the top: sent as 127")
        assertEquals(100, c.state.value.channel(1).level, "the fader itself doesn't move")
    }

    @Test
    fun hardwareMovesUpdateTheDeskWithoutEcho() = runTest {
        val (sim, c) = rig()
        c.pushAll()
        settle()
        c.setMaster(38)
        settle()
        sim.received.clear()
        sim.moveHardwareControl(ch(4, ChannelParam.LEVEL), 30)
        sim.moveHardwareControl(ch(2, ChannelParam.PAN), 0)
        sim.moveHardwareControl(ch(1, ChannelParam.MUTE), 127)
        assertEquals(60, c.state.value.channel(4).level)
        assertEquals(0, c.state.value.channel(2).pan)
        assertTrue(c.state.value.channel(1).mute)
        assertTrue(sim.received.isEmpty(), "nothing echoed back")
    }

    @Test
    fun hardwareUnmuteIsOverriddenWhileMasterMuteIsOn() = runTest {
        val (sim, c) = rig()
        c.pushAll()
        settle()
        c.setMasterMute(true)
        settle()
        sim.received.clear()
        sim.moveHardwareControl(ch(3, ChannelParam.MUTE), 0)
        assertEquals(listOf(MidiMessage.ControlChange(1, 95, 127)), sim.ccs())
        assertFalse(c.state.value.channel(3).mute, "the user's own mute follows the desk")
        settle()
        c.setMasterMute(false)
        assertEquals(0, sim.valueOf(ch(3, ChannelParam.MUTE)))
    }

    @Test
    fun nothingIsSentWhileDisconnectedAndReconnectNeedsAPush() = runTest {
        val (sim, c) = rig()
        sim.close()
        c.setParam(1, ChannelParam.PAN, 0)
        assertTrue(sim.received.isEmpty())
        sim.open(SimulatorTransport.DEVICE)
        assertTrue(c.needsPush.value)
        c.pushAll()
        assertEquals(0, sim.valueOf(ch(1, ChannelParam.PAN)))
    }

    @Test
    fun customProfileIsUsedForSendingAndReceiving() = runTest {
        val (sim, c) = rig()
        val custom = Profile("custom", L6maxLayout.FACTORY_CC_MAP.with(ch(1, ChannelParam.PAN), 107))
        c.profile = custom
        c.setParam(1, ChannelParam.PAN, 5)
        assertTrue(sim.ccs().contains(MidiMessage.ControlChange(1, 107, 5)))
    }

    @Test
    fun namesAreTrimmedAndDefaulted() = runTest {
        val (_, c) = rig()
        c.setName(2, "  Lead vocal mic 1  ")
        assertEquals("Lead vocal m", c.state.value.channel(2).name)
        c.setName(2, "   ")
        assertEquals("CH 2", c.state.value.channel(2).name)
    }

    @Test
    fun autoPushSendsTheDeskOnConnect() = runTest {
        val sim = SimulatorTransport()
        val c = MixerController(sim, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        c.autoPushOnConnect = true
        sim.open(SimulatorTransport.DEVICE)
        assertEquals(MixerController.ALWAYS_SENT_COUNT, sim.ccs().size)
        assertFalse(c.needsPush.value)
    }

    @Test
    fun learnCapturesTheNextCcWithoutApplyingIt() = runTest {
        val (sim, c) = rig()
        val learned = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { c.learnNextControlChange() }
        sim.moveHardwareControl(ch(2, ChannelParam.PAN), 5)
        assertEquals(MidiMessage.ControlChange(1, 74, 5), learned.await())
        assertEquals(64, c.state.value.channel(2).pan, "the desk didn't move while learning")
        sim.moveHardwareControl(ch(2, ChannelParam.PAN), 5)
        assertEquals(5, c.state.value.channel(2).pan, "after learning, moves apply again")
    }

    @Test
    fun resetMixSendsACleanSlateIncludingEfxSends() = runTest {
        val (sim, c) = rig()
        c.setParam(1, ChannelParam.LEVEL, 120)
        c.setMute(3, true)
        c.setSolo(4, true)
        c.setMaster(110)
        settle()
        sim.received.clear()

        assertTrue(c.resetMix())
        for (n in 1..8) {
            assertEquals(0, sim.valueOf(ch(n, ChannelParam.LEVEL)), "CH$n level")
            assertEquals(64, sim.valueOf(ch(n, ChannelParam.PAN)), "CH$n pan")
            assertEquals(64, sim.valueOf(ch(n, ChannelParam.EQ_HIGH)), "CH$n EQ")
            assertEquals(0, sim.valueOf(ch(n, ChannelParam.MUTE)), "CH$n mute")
            assertEquals(0, sim.valueOf(ch(n, ChannelParam.EFX_SEND)), "CH$n EFX send")
        }
        assertEquals(null, sim.valueOf(ch(1, ChannelParam.AUX1)), "AUX sends are left alone")
        assertFalse(c.state.value.anySolo)
        assertEquals(MixerState.MASTER_UNITY, c.state.value.master)
        assertFalse(c.needsPush.value)
    }

    @Test
    fun resetMixNeedsAConnection() = runTest {
        val (sim, c) = rig()
        sim.close()
        c.setParam(1, ChannelParam.LEVEL, 120)
        assertFalse(c.resetMix())
        assertEquals(120, c.state.value.channel(1).level, "nothing changed")
    }

    @Test
    fun anEditBeforeSendDeskSendsOnlyThatControl() = runTest {
        val (sim, c) = rig() // connected, nothing pushed yet
        c.setMute(3, true)
        assertEquals(listOf(MidiMessage.ControlChange(1, 95, 127)), sim.ccs(), "not the whole desk")
        assertTrue(c.needsPush.value, "SEND DESK is still wanted")
        c.pushAll()
        assertEquals(1 + MixerController.ALWAYS_SENT_COUNT, sim.ccs().size, "SEND DESK sends everything")
    }
}

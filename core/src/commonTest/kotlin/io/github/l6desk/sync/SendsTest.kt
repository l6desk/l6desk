package io.github.l6desk.sync

import io.github.l6desk.midi.MidiMessage
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.Control
import io.github.l6desk.model.EfxType
import io.github.l6desk.model.GlobalParam
import io.github.l6desk.model.MixerState
import io.github.l6desk.model.SEND_PARAMS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Sends, EFX TYPE and COMP: unknown until set or reported, and never sent while unknown. */
@OptIn(ExperimentalCoroutinesApi::class)
class SendsTest {
    private fun ch(n: Int, p: ChannelParam) = Control.Channel(n, p)

    private suspend fun TestScope.rig(): Pair<SimulatorTransport, MixerController> {
        val sim = SimulatorTransport()
        val c = MixerController(sim, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        sim.open(SimulatorTransport.DEVICE)
        return sim to c
    }

    @Test
    fun sendsStartUnknownAndArentPushed() = runTest {
        val (sim, c) = rig()
        for (p in SEND_PARAMS) assertNull(c.state.value.channel(1).send(p))
        c.pushAll()
        for (n in 1..8) for (p in SEND_PARAMS) assertNull(sim.valueOf(ch(n, p)), "CH$n $p untouched")
        assertNull(sim.valueOf(Control.Global(GlobalParam.EFX_TYPE)))
        assertNull(sim.valueOf(Control.Global(GlobalParam.COMPRESSOR)))
    }

    @Test
    fun aSendSetInTheAppIsSentAndThenPushedWithTheDesk() = runTest {
        val (sim, c) = rig()
        c.setParam(2, ChannelParam.AUX1, 90)
        assertEquals(90, sim.valueOf(ch(2, ChannelParam.AUX1)))
        sim.received.clear()
        advanceTimeBy(1_000)
        c.pushAll()
        assertEquals(MixerController.ALWAYS_SENT_COUNT + 1, sim.received.size, "the known send joins SEND DESK")
    }

    @Test
    fun aSendTurnedOnTheMixerBecomesKnown() = runTest {
        val (sim, c) = rig()
        sim.moveHardwareControl(ch(3, ChannelParam.AUX2), 40)
        assertEquals(40, c.state.value.channel(3).send(ChannelParam.AUX2))
        sim.received.clear()
        advanceTimeBy(1_000)
        c.pushAll()
        assertEquals(MidiMessage.ControlChange(1, 59, 40), sim.received.filterIsInstance<MidiMessage.ControlChange>().single { it.controller == 59 })
    }

    @Test
    fun efxTypeAndCompressor() = runTest {
        val (sim, c) = rig()
        c.setGlobal(GlobalParam.EFX_TYPE, EfxType.ROOM.value)
        c.setGlobal(GlobalParam.COMPRESSOR, 127)
        assertEquals(43, sim.valueOf(Control.Global(GlobalParam.EFX_TYPE)))
        assertEquals(127, sim.valueOf(Control.Global(GlobalParam.COMPRESSOR)))
        // Changed on the mixer: the desk follows.
        advanceTimeBy(1_000)
        sim.moveHardwareControl(Control.Global(GlobalParam.EFX_TYPE), EfxType.ECHO.value)
        assertEquals(107, c.state.value.globals[GlobalParam.EFX_TYPE])
        assertEquals(EfxType.ECHO, EfxType.of(107))
        assertEquals(EfxType.HALL, EfxType.of(22), "nearest step")
    }

    @Test
    fun resetMixZeroesEfxSendsAndKeepsKnownAuxSends() = runTest {
        val (sim, c) = rig()
        c.setParam(1, ChannelParam.AUX1, 100)
        c.setParam(1, ChannelParam.EFX_SEND, 70)
        advanceTimeBy(1_000)
        c.resetMix()
        assertEquals(0, sim.valueOf(ch(1, ChannelParam.EFX_SEND)))
        assertEquals(0, c.state.value.channel(4).send(ChannelParam.EFX_SEND), "EFX sends are now a known 0")
        assertEquals(100, c.state.value.channel(1).send(ChannelParam.AUX1), "AUX kept")
        assertNull(c.state.value.channel(1).send(ChannelParam.AUX2), "still unknown")
    }

    @Test
    fun knownOutboundValues() {
        val s = MixerState().withChannel(1) { it.with(ChannelParam.SUB_MIX, 33) }
        assertEquals(33, s.knownOutboundValue(ch(1, ChannelParam.SUB_MIX)))
        assertNull(s.knownOutboundValue(ch(2, ChannelParam.SUB_MIX)))
        assertEquals(100, s.knownOutboundValue(ch(2, ChannelParam.LEVEL)), "levels are always known")
        assertNull(s.knownOutboundValue(Control.Global(GlobalParam.EFX_TYPE)))
    }
}

package io.github.l6desk.midi

import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.Control
import io.github.l6desk.model.L6maxLayout
import io.github.l6desk.model.Profile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorTransportTest {
    private val ch8Level = Control.Channel(8, ChannelParam.LEVEL)

    @Test
    fun connectsAndDisconnects() = runTest {
        val sim = SimulatorTransport()
        assertEquals(listOf(SimulatorTransport.DEVICE), sim.devices.value)
        assertEquals(ConnectionState.Disconnected, sim.state.value)
        sim.open(SimulatorTransport.DEVICE)
        assertIs<ConnectionState.Connected>(sim.state.value)
        sim.close()
        assertEquals(ConnectionState.Disconnected, sim.state.value)
    }

    @Test
    fun appliesCcsWithoutEchoingThem() = runTest {
        val sim = SimulatorTransport()
        sim.open(SimulatorTransport.DEVICE)
        val heard = mutableListOf<MidiMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sim.incoming.toList(heard) }

        sim.send(encode(ch8Level, 40))
        assertEquals(40, sim.valueOf(ch8Level))
        assertTrue(heard.isEmpty(), "the real L6max doesn't echo")
    }

    @Test
    fun ignoresOtherMidiChannelsAndSendsWhileDisconnected() = runTest {
        val sim = SimulatorTransport()
        sim.send(encode(ch8Level, 40))
        assertTrue(sim.received.isEmpty())
        sim.open(SimulatorTransport.DEVICE)
        sim.send(MidiMessage.ControlChange(2, 88, 40))
        assertNull(sim.valueOf(ch8Level))
    }

    @Test
    fun hardwareMovesArriveAsCcs() = runTest {
        val sim = SimulatorTransport()
        sim.open(SimulatorTransport.DEVICE)
        val heard = mutableListOf<MidiMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sim.incoming.toList(heard) }

        sim.moveHardwareControl(ch8Level, 43)
        assertEquals(listOf<MidiMessage>(MidiMessage.ControlChange(1, 88, 43)), heard)
        assertEquals(43, sim.valueOf(ch8Level))
    }

    @Test
    fun answersTheEditorHandshakeWithItsCcMap() = runTest {
        val custom = L6maxLayout.FACTORY_CC_MAP.with(ch8Level, 107)
        val sim = SimulatorTransport(ccMap = custom)
        sim.open(SimulatorTransport.DEVICE)
        val heard = mutableListOf<MidiMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sim.incoming.toList(heard) }

        sim.send(L6maxSysEx.IDENTITY_REQUEST)
        sim.send(L6maxSysEx.EDITOR_OPEN)
        assertEquals(2, heard.size)
        assertTrue(L6maxSysEx.isZoomIdentityReply(heard[0]))
        assertEquals(custom, L6maxSysEx.parseCcMap(heard[1]))
    }

    private fun encode(control: Control, value: Int): MidiMessage =
        requireNotNull(CcCodec.encode(control, value, Profile.FACTORY))
}

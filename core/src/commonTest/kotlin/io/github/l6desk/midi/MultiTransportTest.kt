package io.github.l6desk.midi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MultiTransportTest {
    private val l6 = FakeTransport.usb("L6max [hw:4,0,1]")

    @Test
    fun mergesDevicesAndRoutesToTheActiveTransport() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val usb = FakeTransport(l6)
        val sim = SimulatorTransport()
        val multi = MultiTransport(listOf(usb, sim), scope)
        assertEquals(listOf(l6, SimulatorTransport.DEVICE), multi.devices.value)

        multi.open(l6)
        assertEquals(ConnectionState.Connected(l6), multi.state.value)
        multi.send(MidiMessage.ControlChange(1, 81, 10))
        assertEquals(1, usb.sent.size)

        val heard = mutableListOf<MidiMessage>()
        scope.launch { multi.incoming.toList(heard) }
        usb.incoming.emit(MidiMessage.ControlChange(1, 81, 20))
        assertEquals(1, heard.size)

        // Switching to the simulator closes USB and reroutes everything.
        multi.open(SimulatorTransport.DEVICE)
        assertEquals(ConnectionState.Disconnected, usb.state.value)
        assertIs<ConnectionState.Connected>(multi.state.value)
        multi.send(MidiMessage.ControlChange(1, 81, 30))
        assertEquals(1, usb.sent.size, "nothing more went to USB")
        usb.incoming.emit(MidiMessage.ControlChange(1, 81, 40))
        assertEquals(1, heard.size, "USB is no longer listened to")
    }

    @Test
    fun deviceListFollowsHotPlug() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val usb = FakeTransport()
        val multi = MultiTransport(listOf(usb, SimulatorTransport()), scope)
        assertTrue(multi.devices.value.none { it.isL6maxMixerControl })
        usb.devices.value = listOf(l6)
        assertTrue(multi.devices.value.any { it.isL6maxMixerControl })
    }
}

package io.github.l6desk.sync

import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.FakeTransport
import io.github.l6desk.midi.MidiDeviceInfo
import io.github.l6desk.midi.MultiTransport
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.net.NetworkMidiTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {
    private val io = FakeTransport.usb("L6max [hw:4,0,0]")
    private val mixer = FakeTransport.usb("L6max [hw:4,0,1]")
    private val editor = FakeTransport.usb("L6max [hw:4,0,2]")
    private val other = FakeTransport.usb("Midi Through [hw:0,0,0]")

    private fun TestScope.rig(usb: FakeTransport): Triple<MultiTransport, ConnectionManager, FakeTransport> {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val multi = MultiTransport(listOf(usb, SimulatorTransport()), scope)
        return Triple(multi, ConnectionManager(multi, scope), usb)
    }

    private fun MultiTransport.connectedName() = (state.value as? ConnectionState.Connected)?.device?.name

    @Test
    fun picksTheMixerControlPortNotTheOtherL6maxPorts() = runTest {
        val (multi, _, _) = rig(FakeTransport(other, io, mixer, editor))
        assertEquals(mixer.name, multi.connectedName())
    }

    @Test
    fun fallsBackToTheSimulatorAndSwitchesWhenTheL6maxIsPluggedIn() = runTest {
        val usb = FakeTransport(other)
        val (multi, _, _) = rig(usb)
        assertEquals(SimulatorTransport.DEVICE.name, multi.connectedName())
        usb.devices.value = listOf(other, io, mixer, editor)
        assertEquals(mixer.name, multi.connectedName())
    }

    @Test
    fun reconnectsAfterAnUnplugAndReplug() = runTest {
        val usb = FakeTransport(mixer)
        val (multi, _, _) = rig(usb)
        usb.devices.value = emptyList()
        usb.state.value = ConnectionState.Error("unplugged")
        usb.devices.value = listOf(mixer)
        assertEquals(mixer.name, multi.connectedName())
    }

    @Test
    fun reconnectsAfterAnUnplugAndReplugWithNoSimulator() = runTest {
        // The headless bridge: no simulator to fall back on, and the L6max comes back exactly as it was
        // (switched off overnight, on again in the morning).
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val usb = FakeTransport(mixer)
        val multi = MultiTransport(listOf(usb), scope)
        ConnectionManager(multi, scope)
        assertEquals(mixer.name, multi.connectedName())
        usb.devices.value = emptyList()
        usb.state.value = ConnectionState.Error("unplugged")
        usb.devices.value = listOf(mixer)
        assertEquals(mixer.name, multi.connectedName())
    }

    @Test
    fun aBridgeFoundOnTheNetworkComesBeforeTheSimulatorButAfterTheL6max() = runTest {
        val found = MidiDeviceInfo(NetworkMidiTransport.FOUND_PREFIX + "L6Desk Bridge", "L6Desk Bridge (192.168.1.166)", MidiDeviceInfo.Kind.NETWORK)
        val entered = MidiDeviceInfo("bridge:10.0.0.9:5704", "Bridge 10.0.0.9", MidiDeviceInfo.Kind.NETWORK)
        val usb = FakeTransport(entered)
        val (multi, _, _) = rig(usb)
        assertEquals(SimulatorTransport.DEVICE.name, multi.connectedName(), "an entered bridge isn't connected to by itself")
        usb.devices.value = listOf(entered, found)
        assertEquals(found.name, multi.connectedName())
        usb.devices.value = listOf(entered, found, mixer)
        assertEquals(mixer.name, multi.connectedName(), "an L6max on USB still wins")
    }

    @Test
    fun anL6maxConnectedByItselfIsWaitedForWhenUnpluggedNotSwappedForABridge() = runTest {
        // Found on Windows: unplugging the L6max made the app connect to the Pi and ask for its PIN.
        val found = MidiDeviceInfo(NetworkMidiTransport.FOUND_PREFIX + "L6Desk Bridge", "L6Desk Bridge (192.168.1.166)", MidiDeviceInfo.Kind.NETWORK)
        val usb = FakeTransport(mixer, found)
        val (multi, _, _) = rig(usb)
        assertEquals(mixer.name, multi.connectedName())
        usb.devices.value = listOf(found)
        usb.state.value = ConnectionState.Error("unplugged")
        assertEquals(null, multi.connectedName(), "waiting for the L6max: not the bridge, not the simulator")
        assertEquals(listOf(mixer), usb.opened, "nothing else opened")
        usb.devices.value = listOf(found, mixer)
        assertEquals(mixer.name, multi.connectedName(), "back when it's plugged in again")
    }

    @Test
    fun aConnectedBridgeMissingFromTheListForAMomentIsKept() = runTest {
        // Seen on the real Pi: busy Wi-Fi (sending a recording) made discovery miss its rounds, the
        // found bridge left the list, and the desk swapped to the simulator mid-download.
        val found = MidiDeviceInfo(NetworkMidiTransport.FOUND_PREFIX + "L6Desk Bridge", "L6Desk Bridge (192.168.1.166)", MidiDeviceInfo.Kind.NETWORK)
        val usb = FakeTransport(found)
        val (multi, _, _) = rig(usb)
        assertEquals(found.name, multi.connectedName())
        usb.devices.value = emptyList()
        assertEquals(found.name, multi.connectedName(), "not swapped for the simulator")
        usb.devices.value = listOf(found)
        assertEquals(found.name, multi.connectedName())
        assertEquals(listOf(found), usb.opened, "never reopened")
    }

    @Test
    fun theRememberedBridgeIsWaitedForWhenItsLinkIsLost() = runTest {
        val found = MidiDeviceInfo(NetworkMidiTransport.FOUND_PREFIX + "L6Desk Bridge", "L6Desk Bridge (192.168.1.166)", MidiDeviceInfo.Kind.NETWORK)
        val usb = FakeTransport(found)
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val multi = MultiTransport(listOf(usb, SimulatorTransport()), scope)
        ConnectionManager(multi, scope, rememberedId = found.id)
        assertEquals(found.name, multi.connectedName())
        usb.devices.value = emptyList()
        usb.state.value = ConnectionState.Error("Link to bridge lost — reconnecting…")
        assertEquals(null, multi.connectedName(), "waiting for the Pi, not on the simulator")
        assertEquals(listOf(found), usb.opened)
        usb.devices.value = listOf(found)
        assertEquals(found.name, multi.connectedName(), "back when it's found again")
    }

    @Test
    fun aFailedOpenIsNotRetriedUntilTheDeviceListChanges() = runTest {
        val usb = FakeTransport(mixer, failOpen = true)
        rig(usb)
        assertEquals(1, usb.opened.size, "no retry loop")
        usb.failOpen = false
        usb.devices.value = listOf(mixer, other)
        assertEquals(2, usb.opened.size)
    }

    @Test
    fun theUsersChoiceSticks() = runTest {
        val (multi, manager, usb) = rig(FakeTransport(mixer))
        manager.select(SimulatorTransport.DEVICE)
        assertEquals(SimulatorTransport.DEVICE.name, multi.connectedName())
        usb.devices.value = listOf(mixer, other) // a hot-plug event doesn't steal the connection
        assertEquals(SimulatorTransport.DEVICE.name, multi.connectedName())
    }

    @Test
    fun disconnectStaysDisconnected() = runTest {
        val (multi, manager, usb) = rig(FakeTransport(mixer))
        manager.disconnect()
        usb.devices.value = listOf(mixer, other)
        assertEquals(ConnectionState.Disconnected, multi.state.value)
    }

    @Test
    fun reconnectsToTheRememberedDeviceAndReportsPicks() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val usb = FakeTransport(mixer)
        val multi = MultiTransport(listOf(usb, SimulatorTransport()), scope)
        val picks = mutableListOf<String>()
        val manager = ConnectionManager(multi, scope, rememberedId = SimulatorTransport.DEVICE.id, onChoice = { picks += it.id })
        assertEquals(SimulatorTransport.DEVICE.name, multi.connectedName(), "last time's pick wins over the L6max")
        manager.select(mixer)
        assertEquals(listOf(mixer.id), picks)
    }

    @Test
    fun aRememberedDeviceThatIsMissingFallsBackToTheDefaults() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val multi = MultiTransport(listOf(FakeTransport(mixer), SimulatorTransport()), scope)
        ConnectionManager(multi, scope, rememberedId = "gone")
        assertEquals(mixer.name, multi.connectedName())
    }
}

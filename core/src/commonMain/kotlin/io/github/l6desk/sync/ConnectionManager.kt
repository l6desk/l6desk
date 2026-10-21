package io.github.l6desk.sync

import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.MidiDeviceInfo
import io.github.l6desk.midi.MidiTransport
import io.github.l6desk.net.NetworkMidiTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Chooses what to connect to. Until the user picks a device, it connects automatically: the
 * device remembered from last time ([rememberedId]) when present, else the L6max Mixer Control
 * port, else a bridge found on the network, else the simulator. After the user picks a device,
 * it only reconnects to that device when it reappears (e.g. replugged). An L6max it connected to
 * by itself is kept to in the same way: unplugged mid-set, it's waited for, not swapped for a
 * bridge or the simulator (which could also pop up a bridge's PIN question). [onChoice] is told
 * about each pick, to remember it.
 */
class ConnectionManager(
    private val transport: MidiTransport,
    private val scope: CoroutineScope,
    private val rememberedId: String? = null,
    private val onChoice: (MidiDeviceInfo) -> Unit = {},
) {
    private var userChoice: MidiDeviceInfo? = null
    private var userDisconnected = false

    /** The device list when we last tried to connect; after a failure, retry only when it changes. */
    private var devicesAtLastAttempt: List<MidiDeviceInfo>? = null

    init {
        scope.launch {
            combine(transport.devices, transport.state) { devices, state -> devices to state }
                .collect { (devices, state) -> autoConnect(devices, state) }
        }
    }

    /** Connect to [device] and keep to it from now on. */
    fun select(device: MidiDeviceInfo) {
        userChoice = device
        userDisconnected = false
        onChoice(device)
        scope.launch { transport.open(device) }
    }

    /** Disconnect and stop connecting automatically until the user picks a device. */
    fun disconnect() {
        userDisconnected = true
        transport.close()
    }

    private suspend fun autoConnect(devices: List<MidiDeviceInfo>, state: ConnectionState) {
        if (userDisconnected || state is ConnectionState.Connecting) return
        // Connected to an L6max by itself, or to the device picked last time (e.g. the Pi bridge):
        // from now on, keep to it as if the user had picked it (wait for it, don't swap it).
        val connected = (state as? ConnectionState.Connected)?.device
        if (userChoice == null && (connected?.isL6maxMixerControl == true || (connected != null && connected.id == rememberedId))) userChoice = connected
        // Still connected, but missing from the list for a moment (a bridge found on the network
        // misses discovery rounds while its Wi-Fi is busy, e.g. sending a recording): keep it. Seen
        // on the real Pi: the desk dropped to the simulator mid-download, then came back.
        if (connected != null && connected.kind != MidiDeviceInfo.Kind.SIMULATOR && devices.none { it.id == connected.id }) return
        if (state is ConnectionState.Error) {
            if (devices == devicesAtLastAttempt) return
            // Note the list even when there's nothing to open (e.g. just unplugged), so the device coming
            // back exactly as it was still counts as a change.
            devicesAtLastAttempt = devices
        }
        val current = (state as? ConnectionState.Connected)?.device
        val choice = userChoice
        val target = if (choice != null) {
            devices.firstOrNull { it.id == choice.id }
        } else {
            devices.firstOrNull { it.id == rememberedId }
                ?: devices.firstOrNull { it.isL6maxMixerControl }
                ?: devices.firstOrNull { it.id.startsWith(NetworkMidiTransport.FOUND_PREFIX) }
                ?: devices.firstOrNull { it.kind == MidiDeviceInfo.Kind.SIMULATOR }
        }
        if (target != null && target.id != current?.id) {
            devicesAtLastAttempt = devices
            transport.open(target)
        }
    }
}

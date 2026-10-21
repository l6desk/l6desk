package io.github.l6desk.midi

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Something a transport can connect to: a MIDI port, a BLE device, a bridge, or the simulator.
 * [l6maxPort] is 1–3 when this is one of the L6max's own USB MIDI ports (2 = Mixer Control).
 */
data class MidiDeviceInfo(
    val id: String,
    val name: String,
    val kind: Kind,
    val l6maxPort: Int? = null,
    /** A Wi-Fi bridge currently announcing itself on the network (whether or not it was typed in). */
    val found: Boolean = false,
) {
    enum class Kind { USB, BLUETOOTH, NETWORK, SIMULATOR }

    val isL6maxMixerControl: Boolean get() = l6maxPort == L6maxPorts.MIXER_CONTROL
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val device: MidiDeviceInfo) : ConnectionState
    data class Connected(val device: MidiDeviceInfo) : ConnectionState
    data class Error(val reason: String) : ConnectionState
}

/**
 * All MIDI goes through this interface; platform code only implements it (CLAUDE.md, Transports).
 * Implementations: JavaSoundMidiTransport (desktop), AndroidMidiTransport, NetworkMidiTransport,
 * SimulatorTransport.
 */
interface MidiTransport {
    val devices: StateFlow<List<MidiDeviceInfo>>
    val state: StateFlow<ConnectionState>
    val incoming: SharedFlow<MidiMessage>

    suspend fun open(device: MidiDeviceInfo)
    fun close()

    /** Sends [message] if connected; otherwise it is dropped. */
    fun send(message: MidiMessage)
}

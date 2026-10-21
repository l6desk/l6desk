package io.github.l6desk.midi

import io.github.l6desk.model.CcMap
import io.github.l6desk.model.Control
import io.github.l6desk.model.L6maxLayout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An in-process fake L6max for development and tests. It behaves like the real unit as far as
 * it is known (CLAUDE.md, L6max MIDI facts):
 *  - it applies CCs on its MIDI channel and does not echo them back;
 *  - a hardware move ([moveHardwareControl]) sends a CC with the new absolute value;
 *  - sound pads answer their notes ([padsPlayed]) and a pad pressed on it ([pressHardwarePad]) sends
 *    Note On, then Note Off on release;
 *  - it answers the Identity Request and EditorOpen with the replies a real L6max sends.
 *
 * [echo] makes it send every CC it receives straight back, standing in for routes whose echo
 * behaviour is unknown (BLE dongle, bridge) when testing echo suppression.
 */
class SimulatorTransport(
    private val ccMap: CcMap = L6maxLayout.FACTORY_CC_MAP,
    private val midiChannel: Int = 1,
    private val echo: Boolean = false,
) : MidiTransport, CcMapReader {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val _incoming = MutableSharedFlow<MidiMessage>(extraBufferCapacity = 256)
    private val _values = MutableStateFlow<Map<Int, Int>>(emptyMap())

    override val devices: StateFlow<List<MidiDeviceInfo>> = MutableStateFlow(listOf(DEVICE)).asStateFlow()
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()
    override val incoming: SharedFlow<MidiMessage> = _incoming.asSharedFlow()

    /** The simulated mixer's current value for each CC number it has received or sent. */
    val values: StateFlow<Map<Int, Int>> = _values.asStateFlow()

    /** Every message the simulator has received, oldest first. */
    val received = mutableListOf<MidiMessage>()

    /** Pads (1–4) the simulator has been told to play (a Note On for a pad's note), in order. */
    val padsPlayed = mutableListOf<Int>()

    private val connected get() = _state.value is ConnectionState.Connected

    override suspend fun open(device: MidiDeviceInfo) {
        require(device == DEVICE) { "SimulatorTransport only opens ${DEVICE.name}" }
        _state.value = ConnectionState.Connecting(device)
        _state.value = ConnectionState.Connected(device)
    }

    override fun close() {
        _state.value = ConnectionState.Disconnected
    }

    override fun send(message: MidiMessage) {
        if (!connected) return
        received += message
        when {
            message is MidiMessage.ControlChange && message.channel == midiChannel -> {
                _values.value = _values.value + (message.controller to message.value)
                if (echo) _incoming.tryEmit(message)
            }
            message is MidiMessage.NoteOn && message.channel == midiChannel ->
                ccMap.padFor(message.note)?.let { padsPlayed += it }
            message == L6maxSysEx.IDENTITY_REQUEST -> _incoming.tryEmit(IDENTITY_REPLY)
            message == L6maxSysEx.EDITOR_OPEN -> _incoming.tryEmit(L6maxSysEx.buildSnapshot(ccMap))
        }
    }

    override suspend fun readCcMap(device: MidiDeviceInfo): CcMap = L6maxSysEx.parseCcMap(L6maxSysEx.buildSnapshot(ccMap))

    /** The simulated mixer's value for [control], or null if it hasn't been set. */
    fun valueOf(control: Control): Int? = ccMap[control]?.let { _values.value[it] }

    /** Simulates someone turning or pressing [control] on the mixer itself. */
    fun moveHardwareControl(control: Control, value: Int) {
        val cc = requireNotNull(ccMap[control]) { "$control is not mapped in the simulator" }
        _values.value = _values.value + (cc to value)
        if (connected) _incoming.tryEmit(MidiMessage.ControlChange(midiChannel, cc, value))
    }

    /** Simulates someone pressing a scene button on the mixer: it sends only a Program Change. */
    fun recallHardwareScene(program: Int) {
        if (connected) _incoming.tryEmit(MidiMessage.ProgramChange(midiChannel, program))
    }

    /** Simulates someone pressing ([down]) or releasing sound pad [pad] (1–4) on the mixer. */
    fun pressHardwarePad(pad: Int, down: Boolean) {
        val note = requireNotNull(ccMap.padNote(pad)) { "pad $pad is not mapped in the simulator" }
        if (connected) _incoming.tryEmit(if (down) MidiMessage.NoteOn(midiChannel, note, 100) else MidiMessage.NoteOff(midiChannel, note, 64))
    }

    companion object {
        val DEVICE = MidiDeviceInfo("simulator", "L6max Simulator", MidiDeviceInfo.Kind.SIMULATOR)

        /** The Identity Reply captured from a real L6max (firmware "0110" = 1.10). */
        val IDENTITY_REPLY = MidiMessage.SysEx(parseHex("F0 7E 00 06 02 52 72 00 0D 00 30 31 31 30 F7"))
    }
}

package io.github.l6desk.sync

import io.github.l6desk.midi.CcCodec
import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.MidiMessage
import io.github.l6desk.midi.MidiTransport
import io.github.l6desk.model.CHANNEL_COUNT
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.Control
import io.github.l6desk.model.GlobalParam
import io.github.l6desk.model.SEND_PARAMS
import io.github.l6desk.model.SceneSlot
import io.github.l6desk.model.recall
import io.github.l6desk.model.MixerState
import io.github.l6desk.model.Profile
import io.github.l6desk.model.isOn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Single source of truth for the desk (CLAUDE.md, Architecture). Holds the user's [MixerState],
 * sends the device only the values that changed, and applies moves made on the hardware.
 *
 * **Throttling:** each control sends at most once per [throttle]. The first change goes out at
 * once; changes inside the window are coalesced and the latest is sent when it closes, so the
 * final value always arrives. Releasing a control ([setTouching] false) sends it immediately.
 *
 * **Echo suppression:** incoming CCs are ignored for a control the user is touching, and when
 * they repeat any value the app sent for that control within [echoWindow].
 *
 * All timing uses coroutine delays on [scope], so tests run on a virtual clock. Call intents
 * from the thread [scope] runs on (the UI thread in the apps).
 */
class MixerController(
    private val transport: MidiTransport,
    private val scope: CoroutineScope,
    initial: MixerState = MixerState(),
    profile: Profile = Profile.FACTORY,
    private val throttle: Duration = 20.milliseconds,
    private val echoWindow: Duration = 150.milliseconds,
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<MixerState> = _state.asStateFlow()

    val connection: StateFlow<ConnectionState> = transport.state

    private val _mixerSceneRecalls = MutableSharedFlow<SceneSlot>(extraBufferCapacity = 16)

    /**
     * Scenes recalled on the mixer itself (it sends Program Change 0–3 for scenes A–D, and never
     * the recalled values). After one, the desk no longer matches the mixer.
     */
    val mixerSceneRecalls: SharedFlow<SceneSlot> = _mixerSceneRecalls.asSharedFlow()

    private val _pads = MutableStateFlow<Set<Int>>(emptySet())

    /**
     * Sound pads (1–4) currently held, in the app or on the mixer. The mixer reports presses and
     * releases, not playback, so this is "pressed", not "playing".
     */
    val pads: StateFlow<Set<Int>> = _pads.asStateFlow()
    private val padsHeldHere = mutableSetOf<Int>()
    private val padsHeldOnMixer = mutableSetOf<Int>()

    private val _needsPush = MutableStateFlow(false)

    /** True while connected but the device may not match the desk (nothing pushed yet). */
    val needsPush: StateFlow<Boolean> = _needsPush.asStateFlow()

    var profile: Profile = profile
        set(value) {
            field = value
            resetDeviceKnowledge()
            assumeDeviceMatchesDesk()
            _needsPush.value = connected
        }

    /** What the device was last told (or last reported), per control. */
    private val lastSent = mutableMapOf<Control, Int>()

    /** Values waiting for a control's throttle window to close. */
    private val pending = mutableMapOf<Control, Int>()

    /** Open throttle windows, per control. */
    private val cooldowns = mutableMapOf<Control, Job>()

    /** Values sent within the echo window, per control (oldest first). */
    private val recentlySent = mutableMapOf<Control, MutableList<Int>>()

    /** Controls the user currently has a finger (or mouse) on. */
    private val touching = mutableSetOf<Control>()

    /** Send the whole desk as soon as a connection opens (setting "send desk automatically"). */
    var autoPushOnConnect = false

    /** While learning, incoming CCs are captured by [learnNextControlChange] and not applied. */
    private var learning = false

    private val connected get() = transport.state.value is ConnectionState.Connected

    init {
        scope.launch {
            transport.state.collect { s ->
                // A new connection means the device state is unknown again. Until SEND DESK, use the
                // desk's values as the baseline, so an edit sends only the control that changed
                // (not the whole desk, which would silently overwrite the mixer).
                resetDeviceKnowledge()
                assumeDeviceMatchesDesk()
                _needsPush.value = s is ConnectionState.Connected
                if (s !is ConnectionState.Connected) releasePads()
                if (s is ConnectionState.Connected && autoPushOnConnect) pushAll()
            }
        }
        scope.launch {
            transport.incoming.collect { message ->
                if (learning) return@collect
                if (message is MidiMessage.ProgramChange) {
                    if (message.channel == this@MixerController.profile.midiChannel && message.program < SceneSlot.entries.size) {
                        _mixerSceneRecalls.tryEmit(SceneSlot.entries[message.program])
                    }
                    return@collect
                }
                if (message is MidiMessage.NoteOn || message is MidiMessage.NoteOff) {
                    mixerPad(message)
                    return@collect
                }
                CcCodec.decode(message, this@MixerController.profile)?.let { receive(it.control, it.value) }
            }
        }
    }

    // ---- Intents ---------------------------------------------------------------------------

    fun setParam(channel: Int, param: ChannelParam, value: Int) = edit { s -> s.withChannel(channel) { it.with(param, value) } }

    fun setMute(channel: Int, on: Boolean) = edit { s -> s.withChannel(channel) { it.withMute(on) } }

    fun setSolo(channel: Int, on: Boolean) = edit { s -> s.withChannel(channel) { it.copy(solo = on) } }

    fun clearSolo() = edit { s -> s.copy(channels = s.channels.map { it.copy(solo = false) }) }

    fun setMaster(value: Int) = edit { it.copy(master = value) }

    /** EFX TYPE or COMPRESSOR. */
    fun setGlobal(param: GlobalParam, value: Int) = edit { it.withGlobal(param, value) }

    fun setMasterMute(on: Boolean) = edit { it.copy(masterMute = on) }

    fun setName(channel: Int, name: String) =
        _state.update { s -> s.withChannel(channel) { it.copy(name = name.trim().take(12).ifEmpty { "CH $channel" }) } }

    /**
     * The user started or stopped touching [control]. While touched, the hardware can't move it
     * (the finger wins); on release its latest value is sent straight away.
     */
    fun setTouching(control: Control, isTouching: Boolean) {
        if (isTouching) {
            touching += control
        } else {
            touching -= control
            flush(control)
        }
    }

    /**
     * Learn mode: waits for the next Control Change from the mixer (someone moving a control on
     * it) and returns it, without applying it to the desk. Cancel the coroutine to stop waiting.
     */
    suspend fun learnNextControlChange(): MidiMessage.ControlChange {
        learning = true
        try {
            return transport.incoming.filterIsInstance<MidiMessage.ControlChange>().first()
        } finally {
            learning = false
        }
    }

    /** Learn mode for a sound pad: waits for the next Note On (a pad pressed on the mixer). */
    suspend fun learnNextNote(): MidiMessage.NoteOn {
        learning = true
        try {
            return transport.incoming.filterIsInstance<MidiMessage.NoteOn>().first()
        } finally {
            learning = false
        }
    }

    /**
     * Presses ([down]) or releases sound pad [pad] (1–4), like the pad on the mixer: Note On
     * velocity 100, then Note Off velocity 64. The mixer applies the pad's play mode (One-shot,
     * Loop, Hold), so the press must last as long as the finger does. Unmapped pads do nothing.
     */
    fun setPad(pad: Int, down: Boolean) {
        val note = profile.ccMap.padNote(pad) ?: return
        if (down == (pad in padsHeldHere)) return
        if (down) padsHeldHere += pad else padsHeldHere -= pad
        updatePads()
        val channel = profile.midiChannel
        transport.send(if (down) MidiMessage.NoteOn(channel, note, 100) else MidiMessage.NoteOff(channel, note, 64))
    }

    /**
     * "RESET MIX": puts the desk on a clean slate ([MixerState.cleanSlate]), sends all of it, and
     * sets every channel's EFX send to 0 on the mixer (the desk doesn't otherwise control sends).
     * Only while connected, so the mixer and desk end up matching; returns false otherwise.
     */
    fun resetMix(): Boolean {
        if (!connected) return false
        touching.clear()
        _state.update { it.cleanSlate() } // EFX sends become a known 0 and go out with the rest
        pushAll()
        return true
    }

    /**
     * Recalls an app scene: the desk takes [scene] (see [recall]) and, if connected, all of it is
     * sent so the mixer matches. Returns whether it was sent.
     */
    fun recallScene(scene: MixerState): Boolean {
        touching.clear()
        _state.update { it.recall(scene) }
        if (!connected) return false
        pushAll()
        return true
    }

    /** The mixer changed in ways the desk can't see (e.g. its own scene recall): light SEND DESK. */
    fun markMixerChanged() {
        _needsPush.value = connected
    }

    /** Sends the whole desk, so the device matches it ("SEND DESK"). */
    fun pushAll() {
        if (!connected) return
        resetDeviceKnowledge()
        sync()
        _needsPush.value = false
    }

    // ---- Internals ---------------------------------------------------------------------------

    private fun edit(change: (MixerState) -> MixerState) {
        _state.update(change)
        sync()
    }

    /** Applies a value reported by the device after a hardware control moved. */
    private fun receive(control: Control, value: Int) {
        if (control in touching) return // the user's finger wins
        if (recentlySent[control]?.contains(value) == true) return // our own message coming back
        if (control is Control.Global) {
            lastSent[control] = value
            pending -= control
            _state.update { it.withGlobal(control.param, value) }
            return
        }
        if (control !is Control.Channel) return

        lastSent[control] = value
        pending -= control
        val ch = control.channel
        _state.update { s ->
            when (control.param) {
                ChannelParam.LEVEL -> {
                    // Undo the master scaling so the desk fader shows the channel's own position.
                    val fader = s.faderForDeviceLevel(ch, value) ?: return@update s
                    s.withChannel(ch) { it.with(ChannelParam.LEVEL, fader) }
                }
                ChannelParam.MUTE -> s.withChannel(ch) { it.withMute(isOn(value)) }
                else -> s.withChannel(ch) { it.with(control.param, value) }
            }
        }
        if (control.param == ChannelParam.LEVEL) {
            // Rounding can make the rescaled level differ by a step; never echo that back.
            lastSent[control] = state.value.effectiveLevel(ch)
        }
        // Solo and master mute stay authoritative: re-mute anything they still require.
        sync()
    }

    /** Queues every control whose outbound value differs from what the device has. */
    private fun sync() {
        if (!connected) return
        val s = state.value
        for (control in SENT_CONTROLS) s.knownOutboundValue(control)?.let { queue(control, it) }
    }

    private fun queue(control: Control, value: Int) {
        if (control in cooldowns) {
            // Inside the throttle window: remember only the latest value.
            if (value == lastSent[control]) pending -= control else pending[control] = value
            return
        }
        if (value != lastSent[control]) transmit(control, value)
    }

    private fun transmit(control: Control, value: Int) {
        val message = CcCodec.encode(control, value, profile) ?: return
        // Record before sending, so an immediate echo is already recognised.
        lastSent[control] = value
        rememberSent(control, value)
        cooldowns[control] = scope.launch {
            delay(throttle)
            cooldowns -= control
            pending.remove(control)?.let { if (it != lastSent[control]) transmit(control, it) }
        }
        transport.send(message)
    }

    /** Sends a control's pending value now instead of waiting for its window to close. */
    private fun flush(control: Control) {
        val value = pending.remove(control) ?: return
        cooldowns.remove(control)?.cancel()
        if (value != lastSent[control]) transmit(control, value)
    }

    private fun rememberSent(control: Control, value: Int) {
        val values = recentlySent.getOrPut(control) { mutableListOf() }
        values += value
        scope.launch {
            delay(echoWindow)
            values.remove(value)
        }
    }

    private fun mixerPad(message: MidiMessage) {
        val (channel, note, down) = when (message) {
            is MidiMessage.NoteOn -> Triple(message.channel, message.note, true)
            is MidiMessage.NoteOff -> Triple(message.channel, message.note, false)
            else -> return
        }
        if (channel != profile.midiChannel) return
        val pad = profile.ccMap.padFor(note) ?: return
        if (down) padsHeldOnMixer += pad else padsHeldOnMixer -= pad
        updatePads()
    }

    private fun releasePads() {
        padsHeldHere.clear()
        padsHeldOnMixer.clear()
        updatePads()
    }

    private fun updatePads() {
        _pads.value = padsHeldHere + padsHeldOnMixer
    }

    private fun assumeDeviceMatchesDesk() {
        val s = state.value
        for (control in SENT_CONTROLS) s.knownOutboundValue(control)?.let { lastSent[control] = it }
    }

    private fun resetDeviceKnowledge() {
        lastSent.clear()
        pending.clear()
        cooldowns.values.forEach { it.cancel() }
        cooldowns.clear()
        recentlySent.clear()
    }

    companion object {
        /**
         * Controls the desk owns and sends. Sends, EFX TYPE and COMP go out only once known (set
         * in the app or reported by the hardware), so a push never zeroes a monitor mix.
         */
        val SENT_CONTROLS: List<Control> = buildList {
            for (ch in 1..CHANNEL_COUNT) {
                for (p in listOf(
                    ChannelParam.EQ_HIGH, ChannelParam.EQ_MID_FREQ, ChannelParam.EQ_MID, ChannelParam.EQ_LOW,
                    ChannelParam.PAN, ChannelParam.LEVEL, ChannelParam.MUTE,
                ) + SEND_PARAMS) add(Control.Channel(ch, p))
            }
            add(Control.Channel(5, ChannelParam.MONO_X2))
            add(Control.Channel(6, ChannelParam.MONO_X2))
            add(Control.Channel(7, ChannelParam.USB_INPUT))
            add(Control.Channel(8, ChannelParam.USB_INPUT))
            add(Control.Global(GlobalParam.EFX_TYPE))
            add(Control.Global(GlobalParam.COMPRESSOR))
        }

        /** How many controls a push sends for a desk with no known sends or globals. */
        const val ALWAYS_SENT_COUNT = 8 * 7 + 2
    }
}

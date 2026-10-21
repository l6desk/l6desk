package io.github.l6desk.model

/** One channel strip as the user set it. Effective (sent) values are derived in [MixerState]. */
data class ChannelState(
    val number: Int,
    val name: String = "CH $number",
    val params: Map<ChannelParam, Int> = emptyMap(),
    val solo: Boolean = false,
) {
    init {
        require(number in 1..CHANNEL_COUNT) { "Channel must be 1–$CHANNEL_COUNT, was $number" }
        for ((p, v) in params) {
            require(p.availableOn(number)) { "$p is not available on channel $number" }
            require(v in MIDI_VALUE_RANGE) { "$p value $v is outside 0–127" }
        }
    }

    operator fun get(param: ChannelParam): Int = params[param] ?: param.default

    /**
     * A send's level, or null while unknown: the app can't read the mixer's sends, so a send is
     * only known once it's set in the app or reported by the hardware.
     */
    fun send(param: ChannelParam): Int? = params[param]

    val level: Int get() = this[ChannelParam.LEVEL]
    val pan: Int get() = this[ChannelParam.PAN]
    val mute: Boolean get() = isOn(this[ChannelParam.MUTE])
    val monoX2: Boolean get() = isOn(this[ChannelParam.MONO_X2])

    /** CH7/8 listening to USB audio (true) or their own inputs (false); null while unknown. */
    val usbInput: Boolean? get() = params[ChannelParam.USB_INPUT]?.let(::isOn)

    fun with(param: ChannelParam, value: Int): ChannelState = copy(params = params + (param to value))

    fun withMute(on: Boolean): ChannelState = with(ChannelParam.MUTE, toggleValue(on))
}

/**
 * The whole desk as the user set it. The L6max only accepts per-channel parameters, so three
 * desk features exist only in the app and are derived here (see CLAUDE.md, Features):
 *  - solo: any soloed channel mutes every channel that isn't soloed;
 *  - master: a VCA scaling every channel level sent to the device. Unity is at 6 on the 0–10
 *    scale ([MASTER_UNITY]); above it every channel is boosted. The scaled level may go past
 *    127 internally; only the value sent is capped, so lowering the master brings maxed
 *    channels back down in their original proportions;
 *  - master mute: mutes every channel while engaged.
 */
data class MixerState(
    val channels: List<ChannelState> = (1..CHANNEL_COUNT).map { ChannelState(it) },
    val master: Int = MASTER_UNITY,
    val masterMute: Boolean = false,
    val globals: Map<GlobalParam, Int> = emptyMap(),
) {
    init {
        require(channels.size == CHANNEL_COUNT && channels.withIndex().all { (i, c) -> c.number == i + 1 }) {
            "channels must be 1–$CHANNEL_COUNT in order"
        }
        require(master in MIDI_VALUE_RANGE) { "master $master is outside 0–127" }
    }

    fun channel(number: Int): ChannelState = channels[number - 1]

    fun withChannel(number: Int, transform: (ChannelState) -> ChannelState): MixerState =
        copy(channels = channels.map { if (it.number == number) transform(it) else it })

    val anySolo: Boolean get() = channels.any { it.solo }

    /** True when the channel must be muted on the device. */
    fun effectiveMute(number: Int): Boolean {
        val c = channel(number)
        return masterMute || c.mute || (anySolo && !c.solo)
    }

    /** True when the channel is muted only by solo or master mute, not by its own mute. */
    fun impliedMute(number: Int): Boolean = effectiveMute(number) && !channel(number).mute

    /** The channel level sent to the device: fader scaled by master, capped at 127. */
    fun effectiveLevel(number: Int): Int = scale(channel(number).level, master).coerceAtMost(127)

    /** The value the device should hold for [control] (unknown sends and globals as their defaults). */
    fun outboundValue(control: Control): Int = knownOutboundValue(control) ?: when (control) {
        is Control.Global -> control.param.default
        is Control.Channel -> control.param.default
    }

    /**
     * The value the device should hold for [control], or null if the desk doesn't know it (a send,
     * EFX TYPE or COMP that hasn't been set or reported). Unknown values are never sent.
     */
    fun knownOutboundValue(control: Control): Int? = when (control) {
        is Control.Global -> globals[control.param]
        is Control.Channel -> when (control.param) {
            ChannelParam.MUTE -> toggleValue(effectiveMute(control.channel))
            ChannelParam.LEVEL -> effectiveLevel(control.channel)
            in LEARNED_PARAMS -> channel(control.channel).params[control.param]
            else -> channel(control.channel)[control.param]
        }
    }

    fun withGlobal(param: GlobalParam, value: Int): MixerState {
        require(value in MIDI_VALUE_RANGE) { "$param value $value is outside 0–127" }
        return copy(globals = globals + (param to value))
    }

    /**
     * The fader position for channel [number] after the hardware reported level [deviceLevel],
     * at the current master. Clamped to 0–127. Null (keep the fader where it is) when master is
     * 0, or when the hardware reports the maximum and the channel is already at it: its level
     * may be above 127 internally, and the hardware can't say by how much.
     */
    fun faderForDeviceLevel(number: Int, deviceLevel: Int): Int? = when {
        master == 0 -> null
        deviceLevel == 127 && effectiveLevel(number) == 127 -> null
        else -> ((deviceLevel * MASTER_UNITY + master / 2) / master).coerceIn(MIDI_VALUE_RANGE)
    }

    /**
     * A clean slate for a new mix ("RESET MIX"): every level at 0, pan centred, EQ flat, EFX sends
     * at 0, mutes and solos off, master at unity. Channel names, MONO ×2 and the USB inputs (input
     * setup) and the AUX / SUB MIX sends are kept.
     */
    fun cleanSlate(): MixerState = MixerState(
        channels = channels.map { c ->
            ChannelState(
                number = c.number,
                name = c.name,
                params = buildMap {
                    put(ChannelParam.LEVEL, 0)
                    put(ChannelParam.EFX_SEND, 0)
                    if (ChannelParam.MONO_X2.availableOn(c.number)) put(ChannelParam.MONO_X2, c[ChannelParam.MONO_X2])
                    c.params[ChannelParam.USB_INPUT]?.let { put(ChannelParam.USB_INPUT, it) }
                    // AUX and SUB MIX sends may feed stage monitors: keep them as they were.
                    for (p in listOf(ChannelParam.AUX1, ChannelParam.AUX2, ChannelParam.SUB_MIX)) c.send(p)?.let { put(p, it) }
                },
            )
        },
        master = MASTER_UNITY,
        masterMute = false,
        globals = globals,
    )

    companion object {
        /** Master value for unity gain: 6 on the 0–10 scale (6/10 of 127). */
        const val MASTER_UNITY = 76

        /** level × master / unity, rounded to nearest; may exceed 127 (see [effectiveLevel]). */
        fun scale(level: Int, master: Int): Int = (level * master + MASTER_UNITY / 2) / MASTER_UNITY
    }
}

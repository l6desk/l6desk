package io.github.l6desk.model

/** Channel strips on the L6max: 1–4 mono, 5–8 stereo. */
const val CHANNEL_COUNT = 8

/** Every MIDI value is 7-bit. */
val MIDI_VALUE_RANGE = 0..127

/** Per-channel parameters the L6max accepts over MIDI. */
enum class ChannelParam(val label: String, val default: Int, val kind: Kind) {
    EQ_HIGH("EQ HI", 64, Kind.BIPOLAR),
    EQ_MID_FREQ("EQ FREQ", 64, Kind.UNIPOLAR),
    EQ_MID("EQ MID", 64, Kind.BIPOLAR),
    EQ_LOW("EQ LO", 64, Kind.BIPOLAR),
    SUB_MIX("SUB MIX", 0, Kind.UNIPOLAR),
    AUX1("AUX 1", 0, Kind.UNIPOLAR),
    AUX2("AUX 2", 0, Kind.UNIPOLAR),
    EFX_SEND("EFX", 0, Kind.UNIPOLAR),
    PAN("PAN", 64, Kind.BIPOLAR),

    // The level curve (value -> dB) is unverified; 100 matches the web proof of concept.
    LEVEL("LEVEL", 100, Kind.UNIPOLAR),
    MUTE("MUTE", 0, Kind.TOGGLE),

    /** Two mono inputs instead of one stereo input; they still share every setting. */
    MONO_X2("MONO ×2", 0, Kind.TOGGLE),

    /** USB 1/2 on channel 7, USB 3/4 on channel 8. */
    USB_INPUT("USB", 0, Kind.TOGGLE);

    enum class Kind { UNIPOLAR, BIPOLAR, TOGGLE }

    fun availableOn(channel: Int): Boolean = when (this) {
        MONO_X2 -> channel == 5 || channel == 6
        USB_INPUT -> channel == 7 || channel == 8
        else -> channel in 1..CHANNEL_COUNT
    }
}

/** The four per-channel sends, in the order the SENDS view shows them. Their levels start unknown. */
val SEND_PARAMS = listOf(ChannelParam.AUX1, ChannelParam.AUX2, ChannelParam.SUB_MIX, ChannelParam.EFX_SEND)

/**
 * Channel parameters the app can't assume, because the mixer can't be read: they start unknown
 * and are only known (and only ever sent) once set in the app or reported by the hardware. The
 * sends (a monitor mix) and the CH7/8 USB input switches (a wrong guess silences a channel).
 */
val LEARNED_PARAMS = SEND_PARAMS + ChannelParam.USB_INPUT

/** The internal effect types and their EFX TYPE (CC 117) values, verified on the L6max (fw 1.10). */
enum class EfxType(val label: String, val value: Int) {
    AI_NR("AI NR", 0),
    HALL("HALL", 21),
    ROOM("ROOM", 43),
    SPRING("SPRING", 64),
    DELAY("DELAY", 85),
    ECHO("ECHO", 107),
    ;

    companion object {
        /** The type for a received value (the nearest step, in case the device rounds differently). */
        fun of(value: Int): EfxType = entries.minBy { kotlin.math.abs(it.value - value) }
    }
}

/** Mixer-wide parameters the L6max accepts over MIDI. */
enum class GlobalParam(val label: String, val default: Int, val kind: ChannelParam.Kind) {
    EFX_TYPE("EFX TYPE", 0, ChannelParam.Kind.UNIPOLAR),
    COMPRESSOR("COMP", 0, ChannelParam.Kind.TOGGLE),
}

/** One MIDI-addressable control on the mixer. */
sealed interface Control {
    data class Channel(val channel: Int, val param: ChannelParam) : Control {
        init {
            require(param.availableOn(channel)) { "$param is not available on channel $channel" }
        }

        override fun toString() = "CH$channel ${param.label}"
    }

    data class Global(val param: GlobalParam) : Control {
        override fun toString() = param.label
    }
}

/** Toggles are sent as 127 (on) / 0 (off); anything ≥ 64 received counts as on. */
fun toggleValue(on: Boolean): Int = if (on) 127 else 0

fun isOn(value: Int): Boolean = value >= 64

package io.github.l6desk.midi

import io.github.l6desk.model.Control
import io.github.l6desk.model.MIDI_VALUE_RANGE
import io.github.l6desk.model.Profile

/** A mixer control and its 7-bit value, decoded from (or ready to encode as) a CC message. */
data class ControlValue(val control: Control, val value: Int) {
    init {
        require(value in MIDI_VALUE_RANGE) { "value $value is outside 0–127" }
    }
}

/** Converts between mixer controls and Control Change messages using a [Profile]'s CC map. */
object CcCodec {
    /** The CC message for [control] = [value], or null if the control is not mapped. */
    fun encode(control: Control, value: Int, profile: Profile): MidiMessage.ControlChange? {
        val cc = profile.ccMap[control] ?: return null
        return MidiMessage.ControlChange(profile.midiChannel, cc, value)
    }

    /**
     * The control and value a message refers to, or null if it isn't a CC on the profile's
     * MIDI channel or its CC number isn't mapped.
     */
    fun decode(message: MidiMessage, profile: Profile): ControlValue? {
        if (message !is MidiMessage.ControlChange || message.channel != profile.midiChannel) return null
        val control = profile.ccMap.controlFor(message.controller) ?: return null
        return ControlValue(control, message.value)
    }
}

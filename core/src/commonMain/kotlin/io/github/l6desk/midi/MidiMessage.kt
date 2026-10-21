package io.github.l6desk.midi

import io.github.l6desk.model.MIDI_VALUE_RANGE

/** A complete MIDI message. Channels are 1–16; data values are 7-bit. Bytes are 0–255 Ints. */
sealed interface MidiMessage {
    fun toBytes(): List<Int>

    data class ControlChange(val channel: Int, val controller: Int, val value: Int) : MidiMessage {
        init {
            requireChannel(channel)
            require(controller in MIDI_VALUE_RANGE) { "controller $controller is outside 0–127" }
            require(value in MIDI_VALUE_RANGE) { "value $value is outside 0–127" }
        }

        override fun toBytes() = listOf(0xB0 or (channel - 1), controller, value)
    }

    data class ProgramChange(val channel: Int, val program: Int) : MidiMessage {
        init {
            requireChannel(channel)
            require(program in MIDI_VALUE_RANGE) { "program $program is outside 0–127" }
        }

        override fun toBytes() = listOf(0xC0 or (channel - 1), program)
    }

    /** Note On (the L6max's sound pads send and answer these). Velocity 0 is parsed as a [NoteOff]. */
    data class NoteOn(val channel: Int, val note: Int, val velocity: Int) : MidiMessage {
        init {
            requireChannel(channel)
            require(note in MIDI_VALUE_RANGE) { "note $note is outside 0–127" }
            require(velocity in MIDI_VALUE_RANGE) { "velocity $velocity is outside 0–127" }
        }

        override fun toBytes() = listOf(0x90 or (channel - 1), note, velocity)
    }

    data class NoteOff(val channel: Int, val note: Int, val velocity: Int = 64) : MidiMessage {
        init {
            requireChannel(channel)
            require(note in MIDI_VALUE_RANGE) { "note $note is outside 0–127" }
            require(velocity in MIDI_VALUE_RANGE) { "velocity $velocity is outside 0–127" }
        }

        override fun toBytes() = listOf(0x80 or (channel - 1), note, velocity)
    }

    /** System exclusive, including the F0 and F7 framing bytes. */
    data class SysEx(val bytes: List<Int>) : MidiMessage {
        init {
            require(bytes.size >= 2 && bytes.first() == 0xF0 && bytes.last() == 0xF7) { "SysEx must be framed by F0 … F7" }
            require(bytes.subList(1, bytes.size - 1).all { it in MIDI_VALUE_RANGE }) { "SysEx body must be 7-bit" }
        }

        override fun toBytes() = bytes

        companion object {
            fun of(vararg bytes: Int) = SysEx(bytes.toList())
        }
    }

    /** Single-byte real-time messages (clock, start, stop …), F8–FF. */
    data class Realtime(val status: Int) : MidiMessage {
        init {
            require(status in 0xF8..0xFF) { "real-time status must be F8–FF" }
        }

        override fun toBytes() = listOf(status)
    }

    /** Anything else (aftertouch, pitch bend, system common …), kept as raw bytes. */
    data class Other(val bytes: List<Int>) : MidiMessage {
        override fun toBytes() = bytes
    }
}

private fun requireChannel(channel: Int) = require(channel in 1..16) { "MIDI channel $channel is outside 1–16" }

/** Formats bytes as "B0 51 64" for logs. */
fun List<Int>.toHex(): String = joinToString(" ") { it.toString(16).uppercase().padStart(2, '0') }

/** Parses "B0 51 64" (any whitespace) into bytes. */
fun parseHex(text: String): List<Int> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16) }

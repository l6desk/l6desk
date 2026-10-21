package io.github.l6desk.midi

/**
 * Turns a raw MIDI byte stream into complete messages. Android's MidiReceiver and the bridge
 * deliver arbitrary byte chunks, which may split messages, use running status, or interleave
 * real-time bytes, so state is kept across [feed] calls.
 */
class MidiStreamParser {
    private var runningStatus: Int? = null
    private var expectedLength = 0
    private val pending = mutableListOf<Int>()
    private var sysex: MutableList<Int>? = null

    fun feed(bytes: List<Int>): List<MidiMessage> {
        val out = mutableListOf<MidiMessage>()
        for (raw in bytes) feedByte(raw and 0xFF, out)
        return out
    }

    fun feed(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset): List<MidiMessage> =
        feed((offset until offset + count).map { bytes[it].toInt() })

    private fun feedByte(b: Int, out: MutableList<MidiMessage>) {
        when {
            // Real-time bytes may appear anywhere, even inside other messages, and change nothing.
            b >= 0xF8 -> out += MidiMessage.Realtime(b)

            b == 0xF0 -> {
                sysex = mutableListOf(0xF0)
                runningStatus = null
            }

            b == 0xF7 -> sysex?.let {
                it += 0xF7
                out += MidiMessage.SysEx(it.toList())
                sysex = null
            }

            sysex != null && b < 0x80 -> sysex?.add(b)

            b >= 0x80 -> {
                sysex = null // an unterminated SysEx is dropped
                pending.clear()
                if (b in 0xF1..0xF6) {
                    // System common cancels running status. Only F6 (tune request) has no data;
                    // the rest are rare and are collected as Other.
                    runningStatus = null
                    when (b) {
                        0xF6 -> out += MidiMessage.Other(listOf(b))
                        0xF1, 0xF3 -> startSystemCommon(b, 2)
                        0xF2 -> startSystemCommon(b, 3)
                        else -> Unit // F4/F5 undefined: ignore
                    }
                } else {
                    runningStatus = b
                    expectedLength = channelMessageLength(b)
                }
            }

            else -> {
                val status = runningStatus ?: return // stray data byte
                pending += b
                if (pending.size == expectedLength - 1) {
                    out += build(status, pending.toList())
                    pending.clear()
                    if (status >= 0xF0) runningStatus = null // system common has no running status
                }
            }
        }
    }

    private fun startSystemCommon(status: Int, length: Int) {
        runningStatus = status
        expectedLength = length
    }

    private fun channelMessageLength(status: Int) = when (status and 0xF0) {
        0xC0, 0xD0 -> 2
        else -> 3
    }

    private fun build(status: Int, data: List<Int>): MidiMessage {
        val channel = (status and 0x0F) + 1
        return when (status and 0xF0) {
            0xB0 -> MidiMessage.ControlChange(channel, data[0], data[1])
            0xC0 -> MidiMessage.ProgramChange(channel, data[0])
            0x90 -> if (data[1] == 0) MidiMessage.NoteOff(channel, data[0], 0) else MidiMessage.NoteOn(channel, data[0], data[1])
            0x80 -> MidiMessage.NoteOff(channel, data[0], data[1])
            else -> MidiMessage.Other(listOf(status) + data)
        }
    }
}

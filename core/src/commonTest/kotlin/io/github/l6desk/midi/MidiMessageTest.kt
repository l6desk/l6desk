package io.github.l6desk.midi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MidiMessageTest {
    @Test
    fun encodesControlChange() {
        assertEquals(listOf(0xB0, 81, 100), MidiMessage.ControlChange(1, 81, 100).toBytes())
        assertEquals(listOf(0xBF, 0, 127), MidiMessage.ControlChange(16, 0, 127).toBytes())
        assertEquals(listOf(0xC2, 3), MidiMessage.ProgramChange(3, 3).toBytes())
    }

    @Test
    fun rejectsOutOfRangeValues() {
        assertFailsWith<IllegalArgumentException> { MidiMessage.ControlChange(0, 1, 1) }
        assertFailsWith<IllegalArgumentException> { MidiMessage.ControlChange(17, 1, 1) }
        assertFailsWith<IllegalArgumentException> { MidiMessage.ControlChange(1, 128, 1) }
        assertFailsWith<IllegalArgumentException> { MidiMessage.ControlChange(1, 1, -1) }
        assertFailsWith<IllegalArgumentException> { MidiMessage.SysEx(listOf(0xF0, 0x80, 0xF7)) }
        assertFailsWith<IllegalArgumentException> { MidiMessage.SysEx(listOf(0x52, 0xF7)) }
    }

    @Test
    fun hexRoundTrip() {
        assertEquals(listOf(0xB0, 0x51, 0x64), parseHex(" B0 51\n64 "))
        assertEquals("B0 51 64", listOf(0xB0, 0x51, 0x64).toHex())
    }
}

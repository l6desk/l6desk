package io.github.l6desk.midi

import kotlin.test.Test
import kotlin.test.assertEquals

class MidiStreamParserTest {
    private fun cc(cc: Int, v: Int, ch: Int = 1) = MidiMessage.ControlChange(ch, cc, v)

    @Test
    fun completeMessages() {
        assertEquals(listOf(cc(81, 100), cc(93, 127)), MidiStreamParser().feed(parseHex("B0 51 64 B0 5D 7F")))
    }

    @Test
    fun runningStatus() {
        assertEquals(listOf(cc(81, 1), cc(81, 2), cc(82, 3)), MidiStreamParser().feed(parseHex("B0 51 01 51 02 52 03")))
    }

    @Test
    fun messagesSplitAcrossChunks() {
        val p = MidiStreamParser()
        assertEquals(emptyList(), p.feed(parseHex("B0")))
        assertEquals(emptyList(), p.feed(parseHex("51")))
        assertEquals(listOf(cc(81, 100)), p.feed(parseHex("64 51")))
        assertEquals(listOf(cc(81, 5)), p.feed(parseHex("05")))
    }

    @Test
    fun realtimeBytesInsideAMessage() {
        assertEquals(
            listOf(MidiMessage.Realtime(0xF8), cc(81, 100)),
            MidiStreamParser().feed(parseHex("B0 51 F8 64")),
        )
    }

    @Test
    fun sysexAcrossChunksWithRealtimeInside() {
        val p = MidiStreamParser()
        assertEquals(emptyList(), p.feed(parseHex("F0 7E 00")))
        assertEquals(listOf(MidiMessage.Realtime(0xF8)), p.feed(parseHex("06 F8")))
        assertEquals(listOf(MidiMessage.SysEx(parseHex("F0 7E 00 06 01 F7"))), p.feed(parseHex("01 F7")))
    }

    @Test
    fun sysexCancelsRunningStatusAndUnterminatedSysexIsDropped() {
        val p = MidiStreamParser()
        assertEquals(listOf(cc(81, 1)), p.feed(parseHex("B0 51 01 F0 52 00")))
        assertEquals(emptyList(), p.feed(parseHex("51 02")), "running status was cancelled by F0")
        assertEquals(listOf(cc(82, 3)), p.feed(parseHex("B0 52 03")), "new status drops the unterminated SysEx")
    }

    @Test
    fun strayDataBytesAreIgnored() {
        assertEquals(listOf(cc(81, 1)), MidiStreamParser().feed(parseHex("12 34 B0 51 01")))
    }

    @Test
    fun twoByteMessagesAndOtherChannels() {
        assertEquals(
            listOf(MidiMessage.ProgramChange(1, 2), MidiMessage.ProgramChange(1, 3), cc(7, 64, ch = 16)),
            MidiStreamParser().feed(parseHex("C0 02 03 BF 07 40")),
        )
    }

    @Test
    fun parsesTheRealHardwareCaptureInOneChunk() {
        val bytes = HardwareCaptures.HARDWARE_MOVES.flatten()
        val messages = MidiStreamParser().feed(bytes)
        assertEquals(HardwareCaptures.HARDWARE_MOVES.size, messages.size)
        assertEquals(HardwareCaptures.HARDWARE_MOVES, messages.map { it.toBytes() })
    }

    @Test
    fun byteArrayInput() {
        val bytes = byteArrayOf(0xB0.toByte(), 0x51, 0x64)
        assertEquals(listOf(cc(81, 100)), MidiStreamParser().feed(bytes))
    }
}

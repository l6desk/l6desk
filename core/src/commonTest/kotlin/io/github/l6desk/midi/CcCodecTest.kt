package io.github.l6desk.midi

import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.Control
import io.github.l6desk.model.Profile
import io.github.l6desk.model.isOn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CcCodecTest {
    private val factory = Profile.FACTORY

    @Test
    fun encodesUsingTheProfile() {
        val ch8Mute = Control.Channel(8, ChannelParam.MUTE)
        assertEquals(MidiMessage.ControlChange(1, 106, 127), CcCodec.encode(ch8Mute, 127, factory))
        val onChannel5 = factory.copy(midiChannel = 5)
        assertEquals(listOf(0xB4, 106, 0), CcCodec.encode(ch8Mute, 0, onChannel5)?.toBytes())
    }

    @Test
    fun unmappedControlsAreNotSent() {
        val ch1Level = Control.Channel(1, ChannelParam.LEVEL)
        val profile = factory.copy(ccMap = factory.ccMap.with(ch1Level, null))
        assertNull(CcCodec.encode(ch1Level, 100, profile))
    }

    @Test
    fun decodesOnlyMappedCcsOnTheProfileChannel() {
        assertEquals(
            ControlValue(Control.Channel(3, ChannelParam.LEVEL), 40),
            CcCodec.decode(MidiMessage.ControlChange(1, 83, 40), factory),
        )
        assertNull(CcCodec.decode(MidiMessage.ControlChange(2, 83, 40), factory), "wrong MIDI channel")
        assertNull(CcCodec.decode(MidiMessage.ControlChange(1, 32, 40), factory), "unmapped CC")
        assertNull(CcCodec.decode(MidiMessage.ProgramChange(1, 1), factory), "not a CC")
    }

    @Test
    fun decodesTheRealHardwareCapture() {
        val decoded = HardwareCaptures.HARDWARE_MOVES
            .flatMap { MidiStreamParser().feed(it) }
            .map { requireNotNull(CcCodec.decode(it, factory)) { "undecodable: ${it.toBytes().toHex()}" } }

        val touched = decoded.map { it.control as Control.Channel }.map { it.channel to it.param }.toSet()
        assertEquals(
            setOf(1, 2, 3, 4).map { it to ChannelParam.LEVEL }.toSet() +
                setOf(1, 2, 3, 4).map { it to ChannelParam.MUTE } +
                setOf(1, 2).map { it to ChannelParam.PAN },
            touched,
        )
        // Mutes come as 127 (on) then 0 (off).
        val ch1Mute = decoded.filter { it.control == Control.Channel(1, ChannelParam.MUTE) }.map { it.value }
        assertEquals(listOf(127, 0), ch1Mute)
        assertEquals(listOf(true, false), ch1Mute.map(::isOn))
        // Pan moves away from and back to centre (64).
        val ch2Pan = decoded.filter { it.control == Control.Channel(2, ChannelParam.PAN) }.map { it.value }
        assertTrue(ch2Pan.first() > 64 && ch2Pan.last() == 64)
    }
}

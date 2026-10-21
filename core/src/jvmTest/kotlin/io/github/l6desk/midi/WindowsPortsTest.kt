package io.github.l6desk.midi

import kotlin.test.Test
import kotlin.test.assertEquals

/** Windows names a port's input and output differently ("MIDIIN2 (L6max)" / "MIDIOUT2 (L6max)"). */
class WindowsPortsTest {
    @Test
    fun windowsHalvesArePairedIntoPorts() {
        val devices = listOf(
            Triple("L6max", true, false), Triple("L6max", false, true),
            Triple("MIDIIN2 (L6max)", true, false), Triple("MIDIOUT2 (L6max)", false, true),
            Triple("MIDIIN3 (L6max)", true, false), Triple("MIDIOUT3 (L6max)", false, true),
            Triple("Microsoft GS Wavetable Synth", false, true), // output only: not a port
        )
        assertEquals(listOf("L6max", "MIDI2 (L6max)", "MIDI3 (L6max)"), pairPorts(devices))
    }

    @Test
    fun linuxNamesAreUnchanged() {
        val devices = listOf(Triple("L6max [hw:4,0,1]", true, false), Triple("L6max [hw:4,0,1]", false, true))
        assertEquals(listOf("L6max [hw:4,0,1]"), pairPorts(devices))
    }

    @Test
    fun windowsPortNamesAreRecognised() {
        assertEquals(L6maxPorts.MIDI_IO, L6maxPorts.portNumber("L6max"))
        assertEquals(L6maxPorts.MIDI_IO, L6maxPorts.portNumber("ZOOM L6max"))
        assertEquals(L6maxPorts.MIXER_CONTROL, L6maxPorts.portNumber("MIDI2 (L6max)"))
        assertEquals(L6maxPorts.MIXER_CONTROL, L6maxPorts.portNumber("MIDIIN2 (ZOOM L6max)"))
        assertEquals(L6maxPorts.EDITOR, L6maxPorts.portNumber("MIDI3 (ZOOM L6max)"))
        assertEquals(null, L6maxPorts.portNumber("MIDI2 (Other synth)"))
        assertEquals(L6maxPorts.MIXER_CONTROL, L6maxPorts.portNumber("L6max [hw:4,0,1]"), "Linux still")
        assertEquals("MIDI2 (L6max) — Mixer Control Port", L6maxPorts.displayName("MIDI2 (L6max)"))
    }
}

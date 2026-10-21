package io.github.l6desk.midi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class L6maxPortsTest {
    @Test
    fun javaSoundOnLinuxUsesTheAlsaSubdevice() {
        // As listed by Java Sound on Ubuntu with the real L6max (card 4).
        assertEquals(1, L6maxPorts.portNumber("L6max [hw:4,0,0]"))
        assertEquals(2, L6maxPorts.portNumber("L6max [hw:4,0,1]"))
        assertEquals(3, L6maxPorts.portNumber("L6max [hw:4,0,2]"))
        assertEquals(2, L6maxPorts.portNumber("L6max [hw:1,0,1]"), "the card number doesn't matter")
    }

    @Test
    fun portNamesOnOtherPlatforms() {
        assertEquals(2, L6maxPorts.portNumber("L6max Mixer Control Port"))
        assertEquals(3, L6maxPorts.portNumber("L6max for L6 Editor Port"))
        assertEquals(1, L6maxPorts.portNumber("L6max MIDI I/O Port"))
        assertEquals(2, L6maxPorts.portNumber("MIDIIN2 (ZOOM L6max)"))
        assertEquals(2, L6maxPorts.portNumber("MIDIOUT2 (ZOOM L6max)"))
        assertEquals(3, L6maxPorts.portNumber("MIDIOUT3 (ZOOM L6max)"))
    }

    @Test
    fun otherDevicesAreNotL6maxPorts() {
        assertNull(L6maxPorts.portNumber("Midi Through [hw:0,0,0]"))
        assertNull(L6maxPorts.portNumber("WIDI Jack"))
        assertNull(L6maxPorts.portNumber("ZOOM L6 [hw:3,0,1]"), "the original L6 is a different mixer")
    }

    @Test
    fun displayNames() {
        assertEquals("L6max Mixer Control Port (hw:4,0,1)", L6maxPorts.displayName("L6max [hw:4,0,1]"))
        assertEquals("L6max Mixer Control Port", L6maxPorts.displayName("L6max Mixer Control Port"))
        assertEquals("MIDIOUT2 (ZOOM L6max) — Mixer Control Port", L6maxPorts.displayName("MIDIOUT2 (ZOOM L6max)"))
        assertEquals("Some Synth", L6maxPorts.displayName("Some Synth"))
    }
}

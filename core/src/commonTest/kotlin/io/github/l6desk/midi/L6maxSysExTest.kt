package io.github.l6desk.midi

import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.Control
import io.github.l6desk.model.L6maxLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class L6maxSysExTest {
    @Test
    fun parsesTheRealSnapshot() {
        val reply = MidiMessage.SysEx(HardwareCaptures.EDITOR_OPEN_REPLY)
        assertEquals(182, reply.bytes.size)
        assertTrue(L6maxSysEx.isEditorOpenReply(reply))
        assertEquals(L6maxLayout.FACTORY_CC_MAP, L6maxSysEx.parseCcMap(reply))
    }

    @Test
    fun recognisesTheRealIdentityReply() {
        assertTrue(L6maxSysEx.isZoomIdentityReply(SimulatorTransport.IDENTITY_REPLY))
        assertFalse(L6maxSysEx.isZoomIdentityReply(L6maxSysEx.EDITOR_OPEN))
    }

    @Test
    fun snapshotRoundTripWithCustomAndUnmappedEntries() {
        val custom = L6maxLayout.FACTORY_CC_MAP
            .with(Control.Channel(3, ChannelParam.LEVEL), 107) // a CC the factory map leaves free
            .with(Control.Channel(8, ChannelParam.AUX2), null)
        val parsed = L6maxSysEx.parseCcMap(L6maxSysEx.buildSnapshot(custom))
        assertEquals(custom, parsed)
    }

    @Test
    fun rejectsOtherDevicesAndMessages() {
        val l6 = HardwareCaptures.EDITOR_OPEN_REPLY.toMutableList().also { it[5] = 0x03 } // original L6 layout
        assertFailsWith<L6maxProtocolException> { L6maxSysEx.parseCcMap(MidiMessage.SysEx(l6)) }
        assertFailsWith<L6maxProtocolException> { L6maxSysEx.parseCcMap(L6maxSysEx.EDITOR_OPEN) }
        val short = MidiMessage.SysEx(HardwareCaptures.EDITOR_OPEN_REPLY.take(40) + 0xF7)
        assertFailsWith<L6maxProtocolException> { L6maxSysEx.parseCcMap(short) }
    }
}

package io.github.l6desk.midi

import io.github.l6desk.model.CcMap
import io.github.l6desk.model.L6maxLayout

class L6maxProtocolException(message: String) : Exception(message)

/**
 * Read-only use of the L6max Editor port (USB MIDI port 3): fetch the device's CC table.
 * Send [IDENTITY_REQUEST], then [EDITOR_OPEN]; the device replies with a settings snapshot
 * `F0 52 00 00 2A <176-byte payload> F7`. Never send editor write commands.
 * Verified on hardware (fw 1.10); protocol from github.com/philmillman/zoom-l6-companion (MIT).
 */
object L6maxSysEx {
    val IDENTITY_REQUEST = MidiMessage.SysEx.of(0xF0, 0x7E, 0x00, 0x06, 0x01, 0xF7)
    val EDITOR_OPEN = MidiMessage.SysEx.of(0xF0, 0x52, 0x00, 0x00, 0x2B, 0xF7)

    private const val L6MAX_LAYOUT = 0x2E
    private const val PAYLOAD_LENGTH = 176
    private const val CC_TABLE_OFFSET = 28
    /** The four pad notes follow the CC table. */
    private const val PAD_NOTES_OFFSET = CC_TABLE_OFFSET + 94

    /** "Not Mapped" in the snapshot (assumed the same for pads as for CCs; not seen on hardware yet). */
    private const val NOT_MAPPED = 0x7F
    private val HEADER = listOf(0xF0, 0x52, 0x00, 0x00, 0x2A)

    fun isEditorOpenReply(message: MidiMessage): Boolean =
        message is MidiMessage.SysEx && message.bytes.size > HEADER.size + 1 &&
            message.bytes.subList(0, HEADER.size) == HEADER

    /** True for a Universal Identity Reply from a Zoom device. */
    fun isZoomIdentityReply(message: MidiMessage): Boolean =
        message is MidiMessage.SysEx && message.bytes.size >= 7 &&
            message.bytes.subList(0, 6) == listOf(0xF0, 0x7E, message.bytes[2], 0x06, 0x02, 0x52)

    /**
     * The CC map in an EditorOpen reply, with the sound pad notes. CC entries outside the assignable
     * range are "Not Mapped" and are left out. Throws [L6maxProtocolException] if this isn't an L6max snapshot.
     */
    fun parseCcMap(reply: MidiMessage): CcMap {
        if (!isEditorOpenReply(reply)) throw L6maxProtocolException("Not an EditorOpen reply")
        val payload = (reply as MidiMessage.SysEx).bytes.let { it.subList(HEADER.size, it.size - 1) }
        if (payload[0] != L6MAX_LAYOUT) {
            throw L6maxProtocolException("Device layout 0x${payload[0].toString(16)} is not an L6max (0x2e)")
        }
        val tableLength = L6maxLayout.TABLE_ORDER.size
        if (payload.size < CC_TABLE_OFFSET + tableLength) throw L6maxProtocolException("Snapshot too short (${payload.size} bytes)")
        val table = payload.subList(CC_TABLE_OFFSET, CC_TABLE_OFFSET + tableLength)
        val pads = (0 until L6maxLayout.PAD_COUNT).map { i -> payload.getOrNull(PAD_NOTES_OFFSET + i)?.takeIf { it != NOT_MAPPED } }
        return CcMap(
            L6maxLayout.TABLE_ORDER.zip(table)
                .filter { (_, cc) -> L6maxLayout.isAssignableCc(cc) }
                .toMap(),
            pads,
        )
    }

    /** A synthetic EditorOpen reply carrying [map] (used by the simulator and tests). */
    fun buildSnapshot(map: CcMap): MidiMessage.SysEx {
        val payload = MutableList(PAYLOAD_LENGTH) { 0 }
        payload[0] = L6MAX_LAYOUT
        L6maxLayout.TABLE_ORDER.forEachIndexed { i, control -> payload[CC_TABLE_OFFSET + i] = map[control] ?: NOT_MAPPED }
        map.padNotes.forEachIndexed { i, note -> payload[PAD_NOTES_OFFSET + i] = note ?: NOT_MAPPED }
        return MidiMessage.SysEx(HEADER + payload + 0xF7)
    }
}

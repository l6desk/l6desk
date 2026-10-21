package io.github.l6desk.net

/**
 * The Wi-Fi bridge protocol (CLAUDE.md, Wi-Fi bridge protocol), version 8. Deliberately simple:
 * TCP, and every frame is `[type: 1 byte][payload length: 2 bytes, big-endian][payload]`.
 *
 * Session: the client sends [Frame.Hello]; the bridge answers [Frame.Welcome] (or [Frame.Busy]
 * and closes, if another client is in control). Then both sides send [Frame.Midi] frames, the
 * bridge sends [Frame.Status] when its mixer connects or disconnects, and both send
 * [Frame.Ping] every 2 s. Any frame counts as a sign of life; 5 s of silence means the link is lost.
 *
 * Version 2 adds the music player: a bridge with one sends [Frame.Tracks] and [Frame.Playback]
 * (JSON, see PlayerJson) to version-2 clients, which send [Frame.Command]. A version-2 bridge also
 * accepts version-1 clients (no player frames). Unknown frame types are skipped from version 2 on.
 *
 * Version 3 adds playlists (in the player JSON) and uploads: the client sends [Frame.UploadStart]
 * (JSON), then the file in [Frame.UploadData] frames, then an empty [Frame.UploadData] to end it
 * ([Frame.UploadCancel] gives up). The bridge acknowledges each data frame with
 * [Frame.UploadState] (JSON, bytes received so far), and reports converting, done or failed the
 * same way. The client keeps only a little data unacknowledged, and sends MIDI ahead of upload
 * data, so the desk stays responsive while a file goes over.
 *
 * Version 4 adds powering the bridge's computer off: a headless bridge allowed to do it sends
 * [Frame.CanPowerOff] after [Frame.Welcome]; the client may then send [Frame.PowerOff] (e.g. when the
 * app closes), which the bridge echoes back before shutting down. The desktop app's Bridge mode
 * never offers it.
 *
 * Version 5 adds [Frame.BridgeInfo] (JSON, see [BridgeInfo]): what the bridge runs on and how its
 * computer is doing (the Pi's under-voltage alarm and temperature). The bridge sends it after
 * [Frame.Welcome], again whenever a warning appears or clears, and in answer to the client's
 * [Frame.BridgeInfoRequest].
 *
 * Version 6 adds a PIN. A bridge with one answers Hello with [Frame.PinRequired] (16 random
 * bytes); the client sends [Frame.PinAnswer], HMAC-SHA256 of those bytes keyed with the PIN, so
 * the PIN itself never crosses the network. Right: Welcome; wrong: Busy("Wrong PIN"). A bridge
 * with a PIN refuses clients older than v6, asking for the app to be updated.
 *
 * Version 7 adds PAD SOUNDS through the bridge: a bridge that can mount the L6max's memory card
 * (the Pi) sends [Frame.PadCard] (JSON, see PadCardJson; "null" while there's no card) after
 * Welcome and whenever it changes. The client sends [Frame.PadCommand] (set a pad's sound from a
 * library song, clear it, eject the card); the bridge answers each with [Frame.PadResult].
 *
 * Version 8 adds RECORDINGS through the bridge: with the card mounted, the bridge sends
 * [Frame.Recordings] (JSON, see RecordingsJson; "null": no card) after Welcome and on change. The
 * client's [Frame.RecCommand] starts a download; the bridge answers with [Frame.RecState] (JSON:
 * preparing, the files that follow, done or failed) and the files' bytes in [Frame.RecData]
 * frames, in order, sent behind MIDI. The client acknowledges what's arrived (RecCommand ack), and
 * the bridge keeps only a little unacknowledged, as uploads do the other way.
 */
object BridgeProtocol {
    const val VERSION = 8

    /** The oldest client version a bridge still accepts. */
    const val MIN_VERSION = 1
    const val DEFAULT_PORT = 5704
    const val MAX_PAYLOAD = 0xFFFF

    const val TYPE_HELLO = 0x01
    const val TYPE_WELCOME = 0x02
    const val TYPE_MIDI = 0x03
    const val TYPE_PING = 0x04
    const val TYPE_STATUS = 0x05
    const val TYPE_BUSY = 0x06
    const val TYPE_TRACKS = 0x07
    const val TYPE_PLAYBACK = 0x08
    const val TYPE_COMMAND = 0x09
    const val TYPE_UPLOAD_START = 0x0A
    const val TYPE_UPLOAD_DATA = 0x0B
    const val TYPE_UPLOAD_STATE = 0x0C
    const val TYPE_UPLOAD_CANCEL = 0x0D
    const val TYPE_CAN_POWER_OFF = 0x0E
    const val TYPE_POWER_OFF = 0x0F
    const val TYPE_BRIDGE_INFO = 0x10
    const val TYPE_BRIDGE_INFO_REQUEST = 0x11
    const val TYPE_PIN_REQUIRED = 0x12
    const val TYPE_PIN_ANSWER = 0x13
    const val TYPE_PAD_CARD = 0x14
    const val TYPE_PAD_COMMAND = 0x15
    const val TYPE_PAD_RESULT = 0x16
    const val TYPE_RECORDINGS = 0x17
    const val TYPE_REC_COMMAND = 0x18
    const val TYPE_REC_STATE = 0x19
    const val TYPE_REC_DATA = 0x1A

    /** The oldest client version offered uploads and playlists. */
    const val UPLOAD_VERSION = 3

    /** The oldest client version offered powering the bridge off. */
    const val POWER_OFF_VERSION = 4

    /** The oldest client version sent [Frame.BridgeInfo]. */
    const val BRIDGE_INFO_VERSION = 5

    /** The oldest client version that can answer a PIN challenge. */
    const val PIN_VERSION = 6

    /** The oldest client version offered PAD SOUNDS through the bridge. */
    const val PAD_CARD_VERSION = 7

    /** The oldest client version offered RECORDINGS through the bridge. */
    const val RECORDINGS_VERSION = 8

    /** What a bridge says (in Busy) to a wrong PIN; the app looks for it. */
    const val WRONG_PIN = "Wrong PIN"
}

sealed interface Frame {
    /** Client → bridge: the client's protocol version and a name to show at the bridge. */
    data class Hello(val version: Int, val clientName: String) : Frame

    /** Bridge → client: accepted. Whether the bridge has a mixer connected, and its names. */
    data class Welcome(val version: Int, val bridgeName: String, val mixer: MixerStatus) : Frame

    /** Either way: one or more complete MIDI messages, as raw bytes. */
    class Midi(val bytes: ByteArray) : Frame {
        override fun equals(other: Any?) = other is Midi && other.bytes.contentEquals(bytes)
        override fun hashCode() = bytes.contentHashCode()
        override fun toString() = "Midi(${bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }})"
    }

    data object Ping : Frame

    /** Bridge → client: the bridge's mixer connected or went away. */
    data class Status(val mixer: MixerStatus) : Frame

    /** Bridge → client: refused (e.g. another client is in control). The bridge then closes. */
    data class Busy(val reason: String) : Frame

    /** Bridge → client (v2): the music player's track list, as JSON. */
    data class Tracks(val json: String) : Frame

    /** Bridge → client (v2): the music player's state, as JSON. */
    data class Playback(val json: String) : Frame

    /** Client → bridge (v2): a music player command, as JSON. */
    data class Command(val json: String) : Frame

    /** Client → bridge (v3): an upload begins (JSON, see UploadStart). */
    data class UploadStart(val json: String) : Frame

    /** Client → bridge (v3): the next part of upload [id]; empty [bytes] ends it. */
    class UploadData(val id: Int, val bytes: ByteArray) : Frame {
        override fun equals(other: Any?) = other is UploadData && other.id == id && other.bytes.contentEquals(bytes)
        override fun hashCode() = id * 31 + bytes.contentHashCode()
        override fun toString() = "UploadData($id, ${bytes.size} bytes)"
    }

    /** Bridge → client (v3): an upload's progress or result (JSON, see UploadStatus). */
    data class UploadState(val json: String) : Frame

    /** Client → bridge (v3): give up upload [id]. */
    data class UploadCancel(val id: Int) : Frame

    /** Bridge → client (v4): this bridge can power its computer off when asked. */
    data object CanPowerOff : Frame

    /** Client → bridge (v4): power off now. The bridge sends it back as it starts shutting down. */
    data object PowerOff : Frame

    /** Bridge → client (v5): about the bridge and its computer, as JSON (see [BridgeInfo]). */
    data class BridgeInfo(val json: String) : Frame

    /** Client → bridge (v5): send [BridgeInfo] now (fresh, e.g. for a problem report). */
    data object BridgeInfoRequest : Frame

    /** Bridge → client (v6): this bridge has a PIN; answer [nonce] with [PinAnswer]. */
    class PinRequired(val nonce: ByteArray) : Frame {
        override fun equals(other: Any?) = other is PinRequired && other.nonce.contentEquals(nonce)
        override fun hashCode() = nonce.contentHashCode()
    }

    /** Client → bridge (v6): HMAC-SHA256(key = PIN, data = nonce). */
    class PinAnswer(val mac: ByteArray) : Frame {
        override fun equals(other: Any?) = other is PinAnswer && other.mac.contentEquals(mac)
        override fun hashCode() = mac.contentHashCode()
    }

    /** Bridge → client (v7): the L6max's card at the bridge, as JSON ("null": none). */
    data class PadCard(val json: String) : Frame

    /** Client → bridge (v7): a change to the card, as JSON. */
    data class PadCommand(val json: String) : Frame

    /** Bridge → client (v7): how a [PadCommand] went, as JSON. */
    data class PadResult(val json: String) : Frame

    /** Bridge → client (v8): the recordings on the card at the bridge, as JSON ("null": no card). */
    data class Recordings(val json: String) : Frame

    /** Client → bridge (v8): start, acknowledge or cancel a download, as JSON. */
    data class RecCommand(val json: String) : Frame

    /** Bridge → client (v8): how a download is going, as JSON. */
    data class RecState(val json: String) : Frame

    /** Bridge → client (v8): the next part of download [id]'s files. */
    class RecData(val id: Int, val bytes: ByteArray) : Frame {
        override fun equals(other: Any?) = other is RecData && other.id == id && other.bytes.contentEquals(bytes)
        override fun hashCode() = id * 31 + bytes.contentHashCode()
        override fun toString() = "RecData($id, ${bytes.size} bytes)"
    }

    /** A frame type this version doesn't know (from a newer peer); ignored. */
    data class Unknown(val type: Int) : Frame
}

/** The mixer at the bridge end: whether one is connected, and what it's called there. */
data class MixerStatus(val connected: Boolean, val name: String = "")

/** Encodes frames for the wire. */
object FrameEncoder {
    fun encode(frame: Frame): ByteArray {
        val (type, payload) = when (frame) {
            is Frame.Hello -> BridgeProtocol.TYPE_HELLO to byteArrayOf(frame.version.toByte()) + frame.clientName.encodeToByteArray()
            is Frame.Welcome -> BridgeProtocol.TYPE_WELCOME to
                byteArrayOf(frame.version.toByte()) + mixerBytes(frame.mixer) + frame.bridgeName.encodeToByteArray()
            is Frame.Midi -> BridgeProtocol.TYPE_MIDI to frame.bytes
            Frame.Ping -> BridgeProtocol.TYPE_PING to ByteArray(0)
            is Frame.Status -> BridgeProtocol.TYPE_STATUS to mixerBytes(frame.mixer)
            is Frame.Busy -> BridgeProtocol.TYPE_BUSY to frame.reason.encodeToByteArray()
            is Frame.Tracks -> BridgeProtocol.TYPE_TRACKS to frame.json.encodeToByteArray()
            is Frame.Playback -> BridgeProtocol.TYPE_PLAYBACK to frame.json.encodeToByteArray()
            is Frame.Command -> BridgeProtocol.TYPE_COMMAND to frame.json.encodeToByteArray()
            is Frame.UploadStart -> BridgeProtocol.TYPE_UPLOAD_START to frame.json.encodeToByteArray()
            is Frame.UploadData -> BridgeProtocol.TYPE_UPLOAD_DATA to intBytes(frame.id) + frame.bytes
            is Frame.UploadState -> BridgeProtocol.TYPE_UPLOAD_STATE to frame.json.encodeToByteArray()
            is Frame.UploadCancel -> BridgeProtocol.TYPE_UPLOAD_CANCEL to intBytes(frame.id)
            Frame.CanPowerOff -> BridgeProtocol.TYPE_CAN_POWER_OFF to ByteArray(0)
            Frame.PowerOff -> BridgeProtocol.TYPE_POWER_OFF to ByteArray(0)
            is Frame.BridgeInfo -> BridgeProtocol.TYPE_BRIDGE_INFO to frame.json.encodeToByteArray()
            Frame.BridgeInfoRequest -> BridgeProtocol.TYPE_BRIDGE_INFO_REQUEST to ByteArray(0)
            is Frame.PinRequired -> BridgeProtocol.TYPE_PIN_REQUIRED to frame.nonce
            is Frame.PinAnswer -> BridgeProtocol.TYPE_PIN_ANSWER to frame.mac
            is Frame.PadCard -> BridgeProtocol.TYPE_PAD_CARD to frame.json.encodeToByteArray()
            is Frame.PadCommand -> BridgeProtocol.TYPE_PAD_COMMAND to frame.json.encodeToByteArray()
            is Frame.PadResult -> BridgeProtocol.TYPE_PAD_RESULT to frame.json.encodeToByteArray()
            is Frame.Recordings -> BridgeProtocol.TYPE_RECORDINGS to frame.json.encodeToByteArray()
            is Frame.RecCommand -> BridgeProtocol.TYPE_REC_COMMAND to frame.json.encodeToByteArray()
            is Frame.RecState -> BridgeProtocol.TYPE_REC_STATE to frame.json.encodeToByteArray()
            is Frame.RecData -> BridgeProtocol.TYPE_REC_DATA to intBytes(frame.id) + frame.bytes
            is Frame.Unknown -> throw IllegalArgumentException("Can't send an unknown frame")
        }
        require(payload.size <= BridgeProtocol.MAX_PAYLOAD) { "Frame payload too large (${payload.size} bytes)" }
        return byteArrayOf(type.toByte(), (payload.size shr 8).toByte(), payload.size.toByte()) + payload
    }

    private fun intBytes(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    /** `[connected: 1 byte][name length: 1 byte][name]` */
    private fun mixerBytes(m: MixerStatus): ByteArray {
        val name = m.name.encodeToByteArray().let { if (it.size > 255) it.copyOf(255) else it }
        return byteArrayOf(if (m.connected) 1 else 0, name.size.toByte()) + name
    }
}

class BridgeProtocolException(message: String) : Exception(message)

/**
 * Decodes frames from a byte stream that arrives in arbitrary chunks. Keeps any partial frame
 * between [feed] calls. Throws [BridgeProtocolException] on bytes that can't be a frame.
 */
class FrameReader {
    // Bytes not yet decoded are buffer[start until end]; the buffer grows to the largest frame.
    private var buffer = ByteArray(4096)
    private var start = 0
    private var end = 0

    fun feed(bytes: ByteArray, count: Int = bytes.size): List<Frame> {
        if (end + count > buffer.size) {
            val pending = end - start
            val target = if (pending + count > buffer.size) maxOf(buffer.size * 2, pending + count) else buffer.size
            val next = if (target == buffer.size) buffer else ByteArray(target)
            buffer.copyInto(next, 0, start, end)
            buffer = next
            start = 0
            end = pending
        }
        bytes.copyInto(buffer, end, 0, count)
        end += count
        val frames = mutableListOf<Frame>()
        while (end - start >= 3) {
            val length = ((buffer[start + 1].toInt() and 0xFF) shl 8) or (buffer[start + 2].toInt() and 0xFF)
            if (end - start < 3 + length) break
            frames += decode(buffer[start].toInt() and 0xFF, buffer.copyOfRange(start + 3, start + 3 + length))
            start += 3 + length
        }
        if (start == end) {
            start = 0
            end = 0
        }
        return frames
    }

    private fun decode(type: Int, p: ByteArray): Frame = when (type) {
        BridgeProtocol.TYPE_HELLO -> {
            need(p.isNotEmpty(), "empty Hello")
            Frame.Hello(p[0].toInt() and 0xFF, p.copyOfRange(1, p.size).decodeToString())
        }
        BridgeProtocol.TYPE_WELCOME -> {
            need(p.size >= 3, "short Welcome")
            val (mixer, end) = mixer(p, 1)
            Frame.Welcome(p[0].toInt() and 0xFF, p.copyOfRange(end, p.size).decodeToString(), mixer)
        }
        BridgeProtocol.TYPE_MIDI -> Frame.Midi(p)
        BridgeProtocol.TYPE_PING -> Frame.Ping
        BridgeProtocol.TYPE_STATUS -> Frame.Status(mixer(p, 0).first)
        BridgeProtocol.TYPE_BUSY -> Frame.Busy(p.decodeToString())
        BridgeProtocol.TYPE_TRACKS -> Frame.Tracks(p.decodeToString())
        BridgeProtocol.TYPE_PLAYBACK -> Frame.Playback(p.decodeToString())
        BridgeProtocol.TYPE_COMMAND -> Frame.Command(p.decodeToString())
        BridgeProtocol.TYPE_UPLOAD_START -> Frame.UploadStart(p.decodeToString())
        BridgeProtocol.TYPE_UPLOAD_DATA -> {
            need(p.size >= 4, "short upload data")
            Frame.UploadData(int(p), p.copyOfRange(4, p.size))
        }
        BridgeProtocol.TYPE_UPLOAD_STATE -> Frame.UploadState(p.decodeToString())
        BridgeProtocol.TYPE_UPLOAD_CANCEL -> {
            need(p.size >= 4, "short upload cancel")
            Frame.UploadCancel(int(p))
        }
        BridgeProtocol.TYPE_CAN_POWER_OFF -> Frame.CanPowerOff
        BridgeProtocol.TYPE_POWER_OFF -> Frame.PowerOff
        BridgeProtocol.TYPE_BRIDGE_INFO -> Frame.BridgeInfo(p.decodeToString())
        BridgeProtocol.TYPE_BRIDGE_INFO_REQUEST -> Frame.BridgeInfoRequest
        BridgeProtocol.TYPE_PIN_REQUIRED -> Frame.PinRequired(p)
        BridgeProtocol.TYPE_PIN_ANSWER -> Frame.PinAnswer(p)
        BridgeProtocol.TYPE_PAD_CARD -> Frame.PadCard(p.decodeToString())
        BridgeProtocol.TYPE_PAD_COMMAND -> Frame.PadCommand(p.decodeToString())
        BridgeProtocol.TYPE_PAD_RESULT -> Frame.PadResult(p.decodeToString())
        BridgeProtocol.TYPE_RECORDINGS -> Frame.Recordings(p.decodeToString())
        BridgeProtocol.TYPE_REC_COMMAND -> Frame.RecCommand(p.decodeToString())
        BridgeProtocol.TYPE_REC_STATE -> Frame.RecState(p.decodeToString())
        BridgeProtocol.TYPE_REC_DATA -> {
            need(p.size >= 4, "short recording data")
            Frame.RecData(int(p), p.copyOfRange(4, p.size))
        }
        else -> Frame.Unknown(type)
    }

    private fun int(p: ByteArray) =
        ((p[0].toInt() and 0xFF) shl 24) or ((p[1].toInt() and 0xFF) shl 16) or ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)

    private fun mixer(p: ByteArray, at: Int): Pair<MixerStatus, Int> {
        need(p.size >= at + 2, "short mixer status")
        val len = p[at + 1].toInt() and 0xFF
        need(p.size >= at + 2 + len, "short mixer name")
        return MixerStatus(p[at].toInt() != 0, p.copyOfRange(at + 2, at + 2 + len).decodeToString()) to at + 2 + len
    }

    private fun need(ok: Boolean, what: String) {
        if (!ok) throw BridgeProtocolException("Bad frame: $what")
    }
}

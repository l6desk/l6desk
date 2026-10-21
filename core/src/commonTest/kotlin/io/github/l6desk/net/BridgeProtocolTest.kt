package io.github.l6desk.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class BridgeProtocolTest {
    private val frames = listOf(
        Frame.Hello(1, "Galaxy Tab A11"),
        Frame.Welcome(1, "studio-desktop", MixerStatus(true, "L6max Mixer Control Port (hw:4,0,1)")),
        Frame.Midi(byteArrayOf(0xB0.toByte(), 81, 100)),
        Frame.Ping,
        Frame.Status(MixerStatus(false)),
        Frame.Busy("Another device is in control: Galaxy Tab A11"),
    )

    @Test
    fun everyFrameRoundTrips() {
        for (f in frames) assertEquals(listOf(f), FrameReader().feed(FrameEncoder.encode(f)))
    }

    @Test
    fun wireFormatIsTypeLengthPayload() {
        assertEquals(listOf(0x03, 0x00, 0x03, 0xB0, 81, 100), FrameEncoder.encode(frames[2]).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x04, 0x00, 0x00), FrameEncoder.encode(Frame.Ping).map { it.toInt() and 0xFF })
    }

    @Test
    fun framesSplitAndJoinedArbitrarily() {
        val stream = frames.flatMap { FrameEncoder.encode(it).toList() }.toByteArray()
        for (chunk in listOf(1, 2, 5, 17, stream.size)) {
            val reader = FrameReader()
            val got = stream.toList().chunked(chunk).flatMap { reader.feed(it.toByteArray()) }
            assertEquals(frames, got, "chunks of $chunk bytes")
        }
    }

    @Test
    fun sysexSizedMidiFrames() {
        val snapshot = ByteArray(182) { (it % 128).toByte() }
        assertEquals(listOf(Frame.Midi(snapshot)), FrameReader().feed(FrameEncoder.encode(Frame.Midi(snapshot))))
    }

    @Test
    fun playerFramesRoundTripAndUnknownTypesAreSkipped() {
        val frames = listOf(Frame.Tracks("""[{"file":"a.wav"}]"""), Frame.Playback("{}"), Frame.Command("""{"type":"stop"}"""))
        val bytes = frames.map { FrameEncoder.encode(it) }.reduce { a, b -> a + b }
        assertEquals(frames, FrameReader().feed(bytes))
        // A frame type from a newer peer is skipped (with its payload), not fatal.
        val unknown = byteArrayOf(0x7F, 0, 2, 9, 9) + FrameEncoder.encode(Frame.Ping)
        assertEquals(listOf(Frame.Unknown(0x7F), Frame.Ping), FrameReader().feed(unknown))
    }

    @Test
    fun powerOffFramesRoundTripAsEmptyFrames() {
        assertEquals(listOf(Frame.CanPowerOff, Frame.PowerOff), FrameReader().feed(FrameEncoder.encode(Frame.CanPowerOff) + FrameEncoder.encode(Frame.PowerOff)))
        assertEquals(listOf<Byte>(0x0F, 0, 0), FrameEncoder.encode(Frame.PowerOff).toList())
    }

    @Test
    fun bridgeInfoFramesRoundTrip() {
        val info = BridgeInfo(BridgeInfo.KIND_PI, "0.14.0", board = "Raspberry Pi Zero 2 W Rev 1.0", underVoltageNow = false, underVoltageCount = 2, temperatureC = 46.1)
        val frames = listOf(Frame.BridgeInfo(BridgeInfo.encode(info)), Frame.BridgeInfoRequest)
        val decoded = FrameReader().feed(frames.map(FrameEncoder::encode).reduce(ByteArray::plus))
        assertEquals(frames, decoded)
        assertEquals(info, BridgeInfo.decode((decoded[0] as Frame.BridgeInfo).json))
        assertEquals(listOf<Byte>(0x11, 0, 0), FrameEncoder.encode(Frame.BridgeInfoRequest).toList())
    }

    @Test
    fun pinFramesRoundTripAndTheAnswerIsAnHmac() {
        val nonce = ByteArray(16) { it.toByte() }
        val frames = listOf(Frame.PinRequired(nonce), Frame.PinAnswer(BridgePin.answer("2468", nonce)))
        assertEquals(frames, FrameReader().feed(frames.map(FrameEncoder::encode).reduce(ByteArray::plus)))
        assertEquals(32, BridgePin.answer("2468", nonce).size)
        assertTrue(BridgePin.matches("2468", nonce, BridgePin.answer("2468", nonce)))
        assertFalse(BridgePin.matches("2469", nonce, BridgePin.answer("2468", nonce)))
        assertFalse(BridgePin.matches("2468", ByteArray(16), BridgePin.answer("2468", nonce)), "another challenge, another answer")
        // RFC 4231 test case 2: the HMAC itself is standard.
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
        )
        assertEquals(listOf(true, true, false, false, false), listOf("1234", "12345678", "123", "123456789", "12a4").map(BridgePin::isValid))
    }

    @Test
    fun bridgeInfoFromANewerBridgeIsStillRead() {
        val info = BridgeInfo.decode("""{"kind":"pi","version":"0.20.0","fanSpeed":1200,"temperatureC":81.5}""")
        assertEquals("0.20.0", info.version)
        assertEquals(listOf("The Pi is hot (81.5 °C)"), info.warnings)
    }

    @Test
    fun bridgeInfoWarnings() {
        val pi = BridgeInfo(BridgeInfo.KIND_PI, "0.14.0")
        assertEquals(emptyList(), pi.warnings, "nothing known, nothing to warn about")
        assertEquals(listOf("The Pi's power supply is too weak right now (under-voltage)"), pi.copy(underVoltageNow = true, underVoltageCount = 1).warnings)
        assertEquals(listOf("The Pi's power supply dipped too low 3 time(s) (under-voltage)"), pi.copy(underVoltageNow = false, underVoltageCount = 3).warnings)
        assertEquals(listOf("The Pi has little space left for songs (120 MB)"), pi.copy(musicFreeMb = 120).warnings)
        assertEquals(listOf("The bridge's computer is hot (90.0 °C)"), BridgeInfo(BridgeInfo.KIND_DESKTOP, "0.14.0", temperatureC = 90.0).warnings)
    }

    @Test
    fun uploadFramesRoundTripEvenWhenFullSizeAndFedInPieces() {
        val big = ByteArray(BridgeProtocol.MAX_PAYLOAD - 4) { (it * 7).toByte() }
        val frames = listOf(
            Frame.UploadStart("""{"id":3,"playlist":"Set","name":"a.mp3"}"""),
            Frame.UploadData(0x01020304, big),
            Frame.Midi(byteArrayOf(0xB0.toByte(), 81, 100)),
            Frame.UploadData(3, ByteArray(0)),
            Frame.UploadState("""{"id":3,"stage":"DONE"}"""),
            Frame.UploadCancel(-2),
        )
        val stream = frames.flatMap { FrameEncoder.encode(it).toList() }.toByteArray()
        val reader = FrameReader()
        val out = stream.toList().chunked(5_000).flatMap { reader.feed(it.toByteArray()) }
        assertEquals(frames, out)
        assertFailsWith<BridgeProtocolException> { FrameReader().feed(byteArrayOf(BridgeProtocol.TYPE_UPLOAD_DATA.toByte(), 0, 2, 0, 0)) }
    }

    @Test
    fun badInputIsRejected() {
        assertFailsWith<BridgeProtocolException> { FrameReader().feed(byteArrayOf(BridgeProtocol.TYPE_WELCOME.toByte(), 0, 1, 1)) }
        assertFailsWith<IllegalArgumentException> { FrameEncoder.encode(Frame.Midi(ByteArray(70_000))) }
    }

    @Test
    fun padCardFramesRoundTrip() {
        val frames = listOf(Frame.PadCard("null"), Frame.PadCommand("""{"type":"eject","id":1}"""), Frame.PadResult("{}"))
        assertEquals(frames, FrameReader().feed(frames.map { FrameEncoder.encode(it) }.reduce { a, b -> a + b }))
        assertEquals(0x14, FrameEncoder.encode(frames[0])[0].toInt())
    }

    @Test
    fun padCardJsonRoundTrips() {
        val state = io.github.l6desk.padcard.PadCardState("at the Pi", listOf(io.github.l6desk.padcard.PadSound(1, "a.wav", 500, 1)))
        assertEquals(state, io.github.l6desk.padcard.PadCardJson.decodeState(io.github.l6desk.padcard.PadCardJson.encodeState(state)))
        assertEquals(null, io.github.l6desk.padcard.PadCardJson.decodeState(io.github.l6desk.padcard.PadCardJson.encodeState(null)))
        for (c in listOf(io.github.l6desk.padcard.PadCommand.Set(1, 2, "P/01 a.wav"), io.github.l6desk.padcard.PadCommand.Clear(2, 3), io.github.l6desk.padcard.PadCommand.Eject(3))) {
            assertEquals(c, io.github.l6desk.padcard.PadCardJson.decodeCommand(io.github.l6desk.padcard.PadCardJson.encodeCommand(c)))
        }
        val r = io.github.l6desk.padcard.PadResult(4, "Done", error = true)
        assertEquals(r, io.github.l6desk.padcard.PadCardJson.decodeResult(io.github.l6desk.padcard.PadCardJson.encodeResult(r)))
    }
}

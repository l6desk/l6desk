package io.github.l6desk.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The desktop's own player: the Pi's player code on this computer, with uploads copied into its library. */
class LocalPlayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir: File = Files.createTempDirectory("local-music").toFile()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private class SilentOutput : PcmOutput {
        var written = 0L
        override fun write(bytes: ByteArray, length: Int) { written += length }
        override fun pause() = Unit
        override fun resume() = Unit
        override val framesPlayed: Long get() = written / 8
        override fun drain() = Unit
        override fun close() = Unit
    }

    private fun wav(ms: Int = 300): ByteArray {
        val frames = 48 * ms
        val buf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { buf.putShort(1000); buf.putShort(-1000) }
        val out = ByteArrayOutputStream()
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(buf.array()), AudioFormat(48_000f, 16, 2, true, false), frames.toLong()), AudioFileFormat.Type.WAVE, out)
        return out.toByteArray()
    }

    private class BytesSource(override val name: String, private val bytes: ByteArray) : UploadSource {
        private var at = 0
        override val size = bytes.size.toLong()
        override suspend fun read(buffer: ByteArray): Int {
            if (at >= bytes.size) return -1
            val n = minOf(buffer.size, bytes.size - at)
            bytes.copyInto(buffer, 0, at, at + n)
            at += n
            return n
        }
        override fun close() = Unit
    }

    @Test
    fun uploadsGoIntoTheLibraryAndPlay() = runBlocking {
        val output = SilentOutput()
        val player = LocalPlayer(File(dir, "L6Desk"), scope, { output }, { true }, converter = null)
        assertTrue(File(dir, "L6Desk").isDirectory, "the library folder is made")
        player.command(PlayerCommand.CreatePlaylist("Saturday gig"))
        val statuses = mutableListOf<UploadStatus>()
        val done = player.upload(BytesSource("03 - Yesterday.wav", wav()), "Saturday gig") { statuses += it }
        assertEquals(UploadStage.DONE, done.stage, done.message)
        assertTrue(statuses.any { it.stage == UploadStage.RECEIVING && it.received > 0 }, "progress reported")
        assertEquals(listOf("01 Yesterday.wav"), File(dir, "L6Desk/Saturday gig").list().orEmpty().filterNot { it.startsWith(".") })
        val remote = player.player.value!!
        assertEquals(listOf("Yesterday"), remote.tracks.map { it.title })
        assertTrue(remote.canUpload && remote.state.canEditPlaylists && remote.state.ready)

        player.command(PlayerCommand.Play("Saturday gig/01 Yesterday.wav"))
        val end = System.currentTimeMillis() + 5_000
        while (output.written == 0L && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(output.written > 0, "it played into the output")
        player.shutdown()
    }

    @Test
    fun aFileThatIsntMusicIsRefused() = runBlocking {
        val player = LocalPlayer(File(dir, "L6Desk"), scope, { SilentOutput() }, { false }, converter = null)
        val failed = player.upload(BytesSource("notes.txt", "hello".encodeToByteArray()), "Songs")
        assertEquals(UploadStage.FAILED, failed.stage)
        assertEquals(false, player.player.value?.state?.ready, "no L6max audio here")
        assertTrue(player.noAudioHint.contains("this computer"))
        player.shutdown()
    }
}

class FfmpegLocateTest {
    @Test
    fun linuxPlacesThenThePath() {
        // Compared as File paths, so the test also passes on Windows (which writes them with backslashes).
        fun p(s: String) = java.io.File(s).path
        assertEquals(p("/usr/bin/ffmpeg"), FfmpegConverter.locate("/home/x/bin:/opt/x", windows = false) { it.path == p("/usr/bin/ffmpeg") }?.let(::p))
        assertEquals(p("/home/x/bin/ffmpeg"), FfmpegConverter.locate("/home/x/bin:/opt/x", windows = false) { it.path == p("/home/x/bin/ffmpeg") }?.let(::p))
        assertEquals(null, FfmpegConverter.locate("/home/x/bin", windows = false) { false })
    }

    @Test
    fun windowsLooksForFfmpegExeOnThePath() {
        val path = """C:\Windows\system32;"C:\Users\me\AppData\Local\Microsoft\WinGet\Links";C:\tools"""
        val expected = java.io.File("C:\\Users\\me\\AppData\\Local\\Microsoft\\WinGet\\Links", "ffmpeg.exe").path
        assertEquals(expected, FfmpegConverter.locate(path, windows = true) { it.path == expected })
    }
}

class StereoOutputTest {
    @Test
    fun theFirstPairOfFourChannelFramesIsKept() {
        // Two 4-channel frames: (L1, R1, L2, R2), 16-bit little-endian.
        val four = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0, 8, 0)
        val out = ByteArray(8)
        assertEquals(8, JavaSoundPcmOutput.firstPair(four, 2, out))
        assertEquals(listOf<Byte>(1, 0, 2, 0, 5, 0, 6, 0), out.toList(), "USB 1/2 of each frame")
    }
}

class StereoAdapterTest {
    /** 16-bit stereo frames from (left, right) pairs. */
    private fun frames(vararg lr: Pair<Int, Int>) = ByteArray(lr.size * 4).also { b ->
        lr.forEachIndexed { i, (l, r) ->
            b[i * 4] = l.toByte(); b[i * 4 + 1] = (l shr 8).toByte()
            b[i * 4 + 2] = r.toByte(); b[i * 4 + 3] = (r shr 8).toByte()
        }
    }

    @Test
    fun at48kOnlyTheBitDepthChangesLosslessly() {
        val a16 = StereoAdapter(48_000, 16)
        val input = frames(1000 to -1000, 32767 to -32768)
        assertEquals(input.toList(), a16.convert(input, 2).copyOf(a16.length).toList(), "16 bit: unchanged")
        val a24 = StereoAdapter(48_000, 24)
        val out = a24.convert(input, 2).copyOf(a24.length)
        assertEquals(12, out.size)
        assertEquals(listOf<Byte>(0, 0xE8.toByte(), 0x03), out.copyOfRange(0, 3).toList(), "1000 in the top two bytes")
        val a32 = StereoAdapter(48_000, 32)
        assertEquals(16, a32.convert(input, 2).let { a32.length })
    }

    @Test
    fun otherRatesAreResampledSmoothlyAcrossChunks() {
        val up = StereoAdapter(96_000, 16)
        val out = up.convert(frames(0 to 0, 1000 to 1000, 2000 to 2000), 3).copyOf(up.length)
        val lefts = out.toList().chunked(4).map { (it[0].toInt() and 0xFF) or (it[1].toInt() shl 8) }
        assertEquals(listOf(0, 500, 1000, 1500), lefts, "twice the frames, the in-between ones interpolated")

        // 44.1 kHz over many small chunks: the right number of frames, no drift.
        val down = StereoAdapter(44_100, 16)
        var total = 0
        repeat(1000) { total += down.convert(frames(*Array(48) { 100 to 100 }), 48).let { down.length / 4 } }
        assertTrue(kotlin.math.abs(total - 44_100) <= 2, "$total frames for one second")
    }
}

package io.github.l6desk.player

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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** WavReader (plain Kotlin, also used on Android) against Java Sound, which the player used before. */
class WavReaderTest {
    private val dir: File = Files.createTempDirectory("wavreader").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** A 440 Hz tone at half scale, written by Java Sound in [format]. */
    private fun tone(format: AudioFormat, frames: Int = 4800): ByteArray {
        val bytesPer = format.sampleSizeInBits / 8
        val buf = ByteBuffer.allocate(frames * format.channels * bytesPer).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { i ->
            val v = 0.5 * sin(2 * PI * 440 * i / format.sampleRate)
            repeat(format.channels) {
                when {
                    format.encoding == AudioFormat.Encoding.PCM_FLOAT -> buf.putFloat(v.toFloat())
                    bytesPer == 1 -> buf.put(((v * 127).toInt() + 128).toByte())
                    bytesPer == 2 -> buf.putShort((v * 32767).toInt().toShort())
                    bytesPer == 3 -> { val s = (v * 8_388_607).toInt(); buf.put(s.toByte()); buf.put((s shr 8).toByte()); buf.put((s shr 16).toByte()) }
                    else -> buf.putInt((v * Int.MAX_VALUE).toInt())
                }
            }
        }
        val out = ByteArrayOutputStream()
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(buf.array()), format, frames.toLong()), AudioFileFormat.Type.WAVE, out)
        return out.toByteArray()
    }

    private fun readAll(bytes: ByteArray): Pair<WavReader, ShortArray> {
        val r = WavReader(ByteArrayInputStream(bytes))
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1000 * r.frameBytes + 3) // not a whole number of frames
        while (true) {
            val n = r.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        val b = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        return r to ShortArray(out.size() / 2) { b.getShort(it * 2) }
    }

    @Test
    fun the16BitPlayerFormatIsReadUnchanged() {
        val wav = tone(AudioFormat(48_000f, 16, 2, true, false))
        val (r, samples) = readAll(wav)
        assertTrue(r.info.isPlayerFormat)
        assertEquals(100, r.info.durationMs)
        val expected = wav.copyOfRange(wav.size - 4800 * 4, wav.size)
        val b = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN)
        assertContentEquals(ShortArray(4800 * 2) { b.getShort(it * 2) }, samples)
    }

    @Test
    fun otherDepthsAndFloatBecome16Bit() {
        val reference = readAll(tone(AudioFormat(48_000f, 16, 1, true, false))).second
        for (format in listOf(
            AudioFormat(48_000f, 24, 1, true, false),
            AudioFormat(48_000f, 32, 1, true, false),
            AudioFormat(AudioFormat.Encoding.PCM_FLOAT, 48_000f, 32, 1, 4, 48_000f, false),
            AudioFormat(48_000f, 8, 1, false, false),
        )) {
            val (r, samples) = readAll(tone(format))
            assertFalse(r.info.isPlayerFormat, "$format")
            assertEquals(reference.size, samples.size, "$format")
            val tolerance = if (format.sampleSizeInBits == 8) 600 else 2 // 8-bit: one step is 256
            for (i in samples.indices) assertTrue(abs(samples[i] - reference[i]) <= tolerance, "$format at $i: ${samples[i]} vs ${reference[i]}")
        }
    }

    @Test
    fun otherRatesAreResampledTo48k() {
        val (r, samples) = readAll(tone(AudioFormat(44_100f, 16, 2, true, false), frames = 44_100))
        assertEquals(1000, r.info.durationMs)
        val frames = samples.size / 2
        assertTrue(abs(frames - 48_000) <= 2, "one second at 48 kHz: $frames frames")
        // Still a 440 Hz tone at half scale: compare with the ideal one.
        var worst = 0
        for (i in 0 until frames) {
            val ideal = (0.5 * sin(2 * PI * 440 * i / 48_000) * 32767).toInt()
            worst = maxOf(worst, abs(samples[i * 2] - ideal))
        }
        assertTrue(worst < 200, "worst error $worst")
    }

    @Test
    fun javaSoundAgreesOnTheFloatTracksTheL6maxRecords() {
        val wav = tone(AudioFormat(AudioFormat.Encoding.PCM_FLOAT, 48_000f, 32, 2, 8, 48_000f, false))
        val ours = readAll(wav).second
        val pcm16 = AudioFormat(48_000f, 16, 2, true, false)
        val theirs = AudioSystem.getAudioInputStream(pcm16, AudioSystem.getAudioInputStream(ByteArrayInputStream(wav))).readAllBytes()
        val b = ByteBuffer.wrap(theirs).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(theirs.size / 2, ours.size)
        for (i in ours.indices) assertTrue(abs(ours[i] - b.getShort(i * 2)) <= 1, "at $i")
    }

    @Test
    fun extraChunksBeforeTheAudioAreSkipped() {
        // Like the L6max's own tracks: a PAD chunk between fmt and data.
        val plain = tone(AudioFormat(48_000f, 16, 2, true, false))
        val pad = byteArrayOf('P'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte(), ' '.code.toByte(), 3, 0, 0, 0, 0, 0, 0, 0) // odd size + pad byte
        val withPad = plain.copyOfRange(0, 36) + pad + plain.copyOfRange(36, plain.size)
        assertContentEquals(readAll(plain).second, readAll(withPad).second)
    }

    @Test
    fun notAWavIsRefused() {
        assertFailsWith<java.io.IOException> { WavReader(ByteArrayInputStream("hello there, not a wav".toByteArray())) }
        File(dir, "x.wav").writeText("RIFF")
        assertEquals(null, WavInfo.of(File(dir, "x.wav")))
    }

    @Test
    fun theWriterMakesWhatJavaSoundReads() {
        val source = File(dir, "in.wav").apply { writeBytes(tone(AudioFormat(44_100f, 24, 2, true, false), 44_100)) }
        val target = File(dir, "out.wav")
        WavReader(source.inputStream()).use { WavWriter.write(it, target) }
        val f = AudioSystem.getAudioFileFormat(target)
        assertEquals(48_000f, f.format.sampleRate)
        assertEquals(16, f.format.sampleSizeInBits)
        assertEquals(2, f.format.channels)
        assertTrue(abs(f.frameLength - 48_000) <= 2)
        assertTrue(WavInfo.of(target)?.isPlayerFormat == true)
    }
}

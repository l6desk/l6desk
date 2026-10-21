package io.github.l6desk.player

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt

/** What a WAV file holds, from its header: the format and how much audio. */
data class WavInfo(
    /** 1 = integer PCM, 3 = float (WAVE_FORMAT_EXTENSIBLE is read through to its sub-format). */
    val format: Int,
    val channels: Int,
    val sampleRate: Int,
    val bits: Int,
    val dataBytes: Long,
) {
    val frameBytes: Int get() = channels * bits / 8
    val frames: Long get() = if (frameBytes == 0) 0 else dataBytes / frameBytes
    val durationMs: Long get() = if (sampleRate == 0) 0 else frames * 1000 / sampleRate

    /** Already the player's own format: 48 kHz 16-bit integer PCM, mono or stereo. */
    val isPlayerFormat: Boolean get() = format == PCM && bits == 16 && sampleRate == WavReader.RATE && channels in 1..2

    /** A format [WavReader] can play. */
    val isSupported: Boolean
        get() = channels in 1..32 && sampleRate in 8_000..384_000 &&
            ((format == PCM && bits in setOf(8, 16, 24, 32)) || (format == FLOAT && bits in setOf(32, 64)))

    companion object {
        const val PCM = 1
        const val FLOAT = 3
        private const val EXTENSIBLE = 0xFFFE
        private const val MAX_HEADER = 1 shl 20

        /** Reads the header of [input], leaving it at the first byte of audio. Throws if it isn't a WAV. */
        fun read(input: InputStream): WavInfo {
            val head = input.readExactly(12)
            if (head.ascii(0) != "RIFF" || head.ascii(8) != "WAVE") throw IOException("Not a WAV file")
            var fmt: WavInfo? = null
            var skipped = 0L
            while (true) {
                val chunk = input.readExactly(8)
                val id = chunk.ascii(0)
                val size = chunk.le32(4)
                when (id) {
                    "fmt " -> {
                        val b = input.readExactly(size.toInt())
                        var tag = b.le16(0)
                        if (tag == EXTENSIBLE && b.size >= 26) tag = b.le16(24) // sub-format GUID starts with the tag
                        fmt = WavInfo(tag, b.le16(2), b.le32(4).toInt(), b.le16(14), 0)
                        if (size % 2 == 1L) input.skipExactly(1)
                    }
                    "data" -> {
                        val f = fmt ?: throw IOException("WAV file without a format")
                        // Recorders that can't seek leave the size at 0 or 0xFFFFFFFF: play to the end.
                        val bytes = if (size == 0L || size == 0xFFFFFFFFL) Long.MAX_VALUE else size
                        return f.copy(dataBytes = bytes)
                    }
                    else -> {
                        skipped += size
                        if (skipped > MAX_HEADER) throw IOException("No audio found in the WAV file")
                        input.skipExactly(size + (size and 1))
                    }
                }
            }
        }

        /** [file]'s header; null if it isn't a WAV. */
        fun of(file: File): WavInfo? = runCatching { file.inputStream().buffered(4096).use { read(it) } }.getOrNull()
            ?.let { if (it.dataBytes == Long.MAX_VALUE) it.copy(dataBytes = file.length()) else it }
    }
}

/**
 * Reads a WAV file as 16-bit little-endian PCM at 48 kHz, keeping its channel count: integer PCM of
 * 8 to 32 bits and float of 32 or 64 bits are converted, and other sample rates resampled
 * (linearly; uploads are converted properly on arrival, so this is only for files copied in by
 * hand). Plain Kotlin, so the Pi, the desktop and Android all play the same way.
 */
class WavReader(input: InputStream) : Closeable {
    private val input: InputStream = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
    val info: WavInfo = try {
        WavInfo.read(this.input).also { require(it.isSupported) { "This WAV format can't be played (${it.bits}-bit, format ${it.format})" } }
    } catch (e: Exception) {
        input.close()
        throw e
    }
    val channels: Int get() = info.channels

    /** Bytes per frame of what [read] returns. */
    val frameBytes: Int get() = channels * 2

    private var left = info.dataBytes
    private val inFrame = info.frameBytes
    private val step = info.sampleRate.toDouble() / RATE // source frames per output frame
    private val resample = info.sampleRate != RATE
    private var raw = ByteArray(0)

    // Resampling: the two source frames either side of the next output frame, and where between.
    private var prev = IntArray(channels)
    private var next = IntArray(channels)
    private var haveFrames = false
    private var position = 0.0
    private var ended = false

    /** Reads whole frames into [buf]; the number of bytes, or −1 at the end. */
    fun read(buf: ByteArray, offset: Int = 0, length: Int = buf.size): Int {
        val frames = length / frameBytes
        if (frames == 0) return 0
        val n = if (resample) readResampled(buf, offset, frames) else readDirect(buf, offset, frames)
        return if (n == 0) -1 else n * frameBytes
    }

    private fun readDirect(buf: ByteArray, offset: Int, frames: Int): Int {
        val want = minOf(frames.toLong(), left / inFrame).toInt()
        if (want <= 0) return 0
        if (raw.size < want * inFrame) raw = ByteArray(want * inFrame)
        val got = readUpTo(raw, want * inFrame) / inFrame
        left -= got.toLong() * inFrame
        for (f in 0 until got) for (c in 0 until channels) putSample(buf, offset + (f * channels + c) * 2, sampleAt(raw, f * inFrame + c * (info.bits / 8)))
        if (got < want) left = 0
        return got
    }

    private fun readResampled(buf: ByteArray, offset: Int, frames: Int): Int {
        if (!haveFrames) {
            if (!nextFrame(prev) || !nextFrame(next)) return 0.also { ended = true }
            haveFrames = true
        }
        var out = 0
        while (out < frames && !ended) {
            while (position >= 1.0) {
                val t = prev; prev = next; next = t
                if (!nextFrame(next)) {
                    ended = true
                    break
                }
                position -= 1.0
            }
            if (ended) break
            for (c in 0 until channels) {
                val v = prev[c] + (next[c] - prev[c]) * position
                putSample(buf, offset + (out * channels + c) * 2, v.roundToInt())
            }
            out++
            position += step
        }
        return out
    }

    private val one = ByteArray(32 * 8)

    private fun nextFrame(into: IntArray): Boolean {
        if (left < inFrame) return false
        if (readUpTo(one, inFrame) < inFrame) {
            left = 0
            return false
        }
        left -= inFrame
        for (c in 0 until channels) into[c] = sampleAt(one, c * (info.bits / 8))
        return true
    }

    /** The sample at [at] in [b], as 16-bit (clipped). */
    private fun sampleAt(b: ByteArray, at: Int): Int = when {
        info.format == WavInfo.FLOAT && info.bits == 32 -> floatTo16(java.lang.Float.intBitsToFloat(le32i(b, at)).toDouble())
        info.format == WavInfo.FLOAT -> floatTo16(java.lang.Double.longBitsToDouble((le32i(b, at).toLong() and 0xFFFFFFFFL) or (le32i(b, at + 4).toLong() shl 32)))
        info.bits == 8 -> ((b[at].toInt() and 0xFF) - 128) shl 8
        info.bits == 16 -> (b[at].toInt() and 0xFF) or (b[at + 1].toInt() shl 8)
        info.bits == 24 -> ((b[at + 1].toInt() and 0xFF) or (b[at + 2].toInt() shl 8)) // the top 16 bits
        else -> ((b[at + 2].toInt() and 0xFF) or (b[at + 3].toInt() shl 8))
    }

    private fun floatTo16(v: Double): Int = (v.coerceIn(-1.0, 1.0) * 32767).roundToInt()

    private fun le32i(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or (b[at + 3].toInt() shl 24)

    private fun putSample(buf: ByteArray, at: Int, value: Int) {
        val v = value.coerceIn(-32768, 32767)
        buf[at] = v.toByte()
        buf[at + 1] = (v shr 8).toByte()
    }

    private fun readUpTo(b: ByteArray, n: Int): Int {
        var total = 0
        while (total < n) {
            val r = input.read(b, total, n - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    override fun close() = input.close()

    companion object {
        const val RATE = 48_000
    }
}

private fun ByteArray.ascii(at: Int) = String(this, at, 4, Charsets.US_ASCII)
private fun ByteArray.le16(at: Int) = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)
private fun ByteArray.le32(at: Int): Long =
    ((this[at].toLong() and 0xFF) or ((this[at + 1].toLong() and 0xFF) shl 8) or ((this[at + 2].toLong() and 0xFF) shl 16) or ((this[at + 3].toLong() and 0xFF) shl 24))

private fun InputStream.readExactly(n: Int): ByteArray {
    val b = ByteArray(n)
    var total = 0
    while (total < n) {
        val r = read(b, total, n - total)
        if (r < 0) throw EOFException("The file ends early")
        total += r
    }
    return b
}

private fun InputStream.skipExactly(n: Long) {
    var left = n
    while (left > 0) {
        val s = skip(left)
        if (s <= 0) {
            if (read() < 0) throw EOFException("The file ends early")
            left--
        } else {
            left -= s
        }
    }
}

/** Writes 16-bit 48 kHz PCM WAV files. */
object WavWriter {
    /** Copies everything [reader] gives into [target] as a 16-bit 48 kHz WAV with its channel count. */
    fun write(reader: WavReader, target: File) {
        java.io.RandomAccessFile(target, "rw").use { out ->
            out.setLength(0)
            out.write(header(reader.channels, 0))
            val buf = ByteArray(64 * 1024 / reader.frameBytes * reader.frameBytes)
            var data = 0L
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                data += n
            }
            out.seek(0)
            out.write(header(reader.channels, data))
        }
    }

    /** A plain 44-byte header for 16-bit 48 kHz PCM. */
    fun header(channels: Int, dataBytes: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(WavReader.RATE)
        b.putInt(WavReader.RATE * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        return b.array()
    }
}

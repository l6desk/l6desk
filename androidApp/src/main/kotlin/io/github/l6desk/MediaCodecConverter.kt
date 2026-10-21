package io.github.l6desk

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.l6desk.player.AudioConverter
import io.github.l6desk.player.WavReader
import io.github.l6desk.player.WavWriter
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Converts uploads on the tablet (MP3, AAC/M4A, FLAC, WAV in other formats) to the player's 48 kHz
 * 16-bit WAV with Android's own decoders (no ffmpeg on Android): decoded to a WAV at the file's own
 * rate, then resampled to 48 kHz by [WavReader] if needed.
 */
object MediaCodecConverter : AudioConverter {
    override fun convert(input: File, output: File) {
        val decoded = File(output.parentFile, output.name + ".decoded")
        try {
            val rate = decode(input, decoded)
            if (rate == WavReader.RATE) {
                if (!decoded.renameTo(output)) throw IOException("Couldn't store the converted file")
            } else {
                WavReader(decoded.inputStream()).use { WavWriter.write(it, output) }
            }
        } finally {
            decoded.delete()
        }
    }

    /** Decodes [input]'s first audio track into a 16-bit WAV at its own rate; returns the rate. */
    private fun decode(input: File, target: File): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.path)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IOException("${input.name} has no audio")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: throw IOException("Unknown audio format")
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inFormat, null, null, 0)
            codec.start()
            var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var data = 0L
            RandomAccessFile(target, "rw").use { out ->
                out.setLength(0)
                out.write(ByteArray(44)) // the header, written once the size is known
                var chunk = ByteArray(0)
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buffer = codec.getInputBuffer(i) ?: throw IOException("Decoder error")
                            val n = extractor.readSampleData(buffer, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        o >= 0 -> {
                            val buffer = codec.getOutputBuffer(o)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset)
                                if (float) {
                                    // Float to 16-bit (decoders give 16-bit unless asked, but some don't).
                                    val samples = info.size / 4
                                    if (chunk.size < samples * 2) chunk = ByteArray(samples * 2)
                                    val fb = buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                                    for (s in 0 until samples) {
                                        val v = (fb.get() * 32767f).toInt().coerceIn(-32768, 32767)
                                        chunk[s * 2] = v.toByte(); chunk[s * 2 + 1] = (v shr 8).toByte()
                                    }
                                    out.write(chunk, 0, samples * 2)
                                    data += samples * 2
                                } else {
                                    if (chunk.size < info.size) chunk = ByteArray(info.size)
                                    buffer.get(chunk, 0, info.size)
                                    out.write(chunk, 0, info.size)
                                    data += info.size
                                }
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
                out.seek(0)
                out.write(header(channels, rate, data))
            }
            codec.stop()
            codec.release()
            if (data == 0L) throw IOException("${input.name} has no audio Android can decode")
            return rate
        } finally {
            extractor.release()
        }
    }

    private fun header(channels: Int, rate: Int, dataBytes: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
        b.putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        return b.array()
    }
}

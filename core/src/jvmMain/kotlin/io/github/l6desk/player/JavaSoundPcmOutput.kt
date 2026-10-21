package io.github.l6desk.player

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine

/**
 * The L6max's USB audio playback through Java Sound: 4 channels (USB 1/2 + USB 3/4), 48 kHz,
 * 16 bit (on Linux the `L6max [plughw:C,0]` device, which converts to the L6max's native format).
 *
 * Where only a stereo device is offered ([stereo]: Windows, where Java drives at most 2 channels per
 * device, "Line (ZOOM L6max Audio)"), only USB 1/2 is played: the player is told there's one pair.
 */
class JavaSoundPcmOutput private constructor(private val line: SourceDataLine, private val stereo: StereoAdapter?) : PcmOutput {
    private var stereoBuffer = ByteArray(0)

    override fun write(bytes: ByteArray, length: Int) {
        if (stereo == null) {
            line.write(bytes, 0, length)
            return
        }
        val frames = length / FOUR_CHANNEL_FRAME
        if (stereoBuffer.size < frames * STEREO_FRAME) stereoBuffer = ByteArray(frames * STEREO_FRAME)
        firstPair(bytes, frames, stereoBuffer)
        val out = stereo.convert(stereoBuffer, frames)
        line.write(out, 0, stereo.length)
    }

    override fun pause() = line.stop()

    override fun resume() = line.start()

    /** In the player's 48 kHz frames, whatever rate the device runs at. */
    override val framesPlayed: Long get() = stereo?.let { line.longFramePosition * RATE / it.rate } ?: line.longFramePosition

    override fun drain() = line.drain()

    override fun close() = line.close()

    companion object {
        val FORMAT = AudioFormat(48_000f, 16, 4, true, false)
        private val LINE = DataLine.Info(SourceDataLine::class.java, FORMAT)
        private val STEREO_FORMAT = AudioFormat(48_000f, 16, 2, true, false)
        private val STEREO_LINE = DataLine.Info(SourceDataLine::class.java, STEREO_FORMAT)
        private const val FOUR_CHANNEL_FRAME = 8
        private const val STEREO_FRAME = 4
        private const val RATE = 48_000L

        /**
         * What a stereo-only device is asked for, in turn: the player's own format first, then the
         * ones Windows devices are commonly set to (Zoom's driver only takes the one set in Windows'
         * sound settings). Converted to on the way out ([StereoAdapter]).
         */
        internal val STEREO_CANDIDATES = listOf(48_000 to 16, 48_000 to 24, 48_000 to 32, 44_100 to 16, 44_100 to 24, 96_000 to 16, 96_000 to 24, 44_100 to 32, 96_000 to 32)

        /** The format the stereo device last opened with (for problem reports). */
        @Volatile
        var lastStereoFormat: String? = null
            private set

        /** Why each format tried last time was refused (for problem reports). */
        @Volatile
        var lastStereoFailures: List<String> = emptyList()
            private set

        /** The L6max's playback device, if it's connected, and whether it only takes stereo. */
        fun findDevice(): Pair<Mixer.Info, Boolean>? {
            val l6max = AudioSystem.getMixerInfo().filter { it.name.contains("L6max", ignoreCase = true) }
            fun supports(info: Mixer.Info, line: DataLine.Info) = runCatching { AudioSystem.getMixer(info).isLineSupported(line) }.getOrDefault(false)
            return l6max.firstOrNull { supports(it, LINE) }?.let { it to false }
                ?: l6max.firstOrNull { supports(it, STEREO_LINE) }?.let { it to true }
        }

        fun findMixer(): Mixer.Info? = findDevice()?.first

        fun isAvailable(): Boolean = findDevice() != null

        /** USB pairs the player can use: 2 (USB 1/2 and 3/4), or 1 on a stereo-only device. */
        fun pairs(): Int = if (findDevice()?.second == true) 1 else 2

        /** Copies the first channel pair (USB 1/2) of [frames] 4-channel frames into [out]; returns the bytes written. */
        internal fun firstPair(fourChannel: ByteArray, frames: Int, out: ByteArray): Int {
            for (i in 0 until frames) fourChannel.copyInto(out, i * STEREO_FRAME, i * FOUR_CHANNEL_FRAME, i * FOUR_CHANNEL_FRAME + STEREO_FRAME)
            return frames * STEREO_FRAME
        }

        /**
         * Every audio device Java Sound offers for playback, and whether it takes the player's
         * 4-channel and 2-channel 48 kHz formats (for problem reports: how a device the player
         * doesn't use, e.g. on Windows, can be found).
         */
        fun describeAll(): List<String> = runCatching {
            val stereo = DataLine.Info(SourceDataLine::class.java, AudioFormat(48_000f, 16, 2, true, false))
            AudioSystem.getMixerInfo().mapNotNull { info ->
                val mixer = runCatching { AudioSystem.getMixer(info) }.getOrNull() ?: return@mapNotNull null
                if (mixer.sourceLineInfo.none { it is DataLine.Info }) return@mapNotNull null // capture or port mixers
                val four = runCatching { mixer.isLineSupported(LINE) }.getOrDefault(false)
                val two = runCatching { mixer.isLineSupported(stereo) }.getOrDefault(false)
                val channels = mixer.sourceLineInfo.filterIsInstance<DataLine.Info>().flatMap { it.formats.toList() }
                    .map { it.channels }.filter { it > 0 }.distinct().sorted()
                "${info.name} (${info.description}): 4ch=$four 2ch=$two formats=$channels"
            }
        }.getOrElse { listOf("Java Sound failed: ${it.message}") }

        fun open(): PcmOutput {
            val (info, stereo) = findDevice() ?: error("the L6max's USB audio isn't connected")
            val mixer = AudioSystem.getMixer(info)
            if (!stereo) {
                val line = mixer.getLine(LINE) as SourceDataLine
                line.open(FORMAT, FORMAT.frameSize * 48_000 / 5) // 200 ms of buffer
                line.start()
                return JavaSoundPcmOutput(line, null)
            }
            val failures = mutableListOf<String>()
            for ((rate, bits) in STEREO_CANDIDATES) {
                val format = AudioFormat(rate.toFloat(), bits, 2, true, false)
                // With our buffer size first, then the device's own (some drivers are particular).
                for (buffer in listOf(format.frameSize * rate / 5, null)) {
                    var line: SourceDataLine? = null
                    try {
                        line = mixer.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
                        if (buffer != null) line.open(format, buffer) else line.open(format)
                        line.start()
                        lastStereoFormat = "$rate Hz, $bits bit" + if (buffer == null) " (device's buffer)" else ""
                        lastStereoFailures = failures.toList()
                        return JavaSoundPcmOutput(line, StereoAdapter(rate, bits))
                    } catch (e: Exception) {
                        runCatching { line?.close() }
                        failures += "$rate/$bits${if (buffer == null) "/default buffer" else ""}: ${e::class.simpleName}: ${e.message}"
                    }
                }
            }
            lastStereoFailures = failures.toList()
            error("the L6max's audio device takes none of the formats tried. Windows said: ${failures.map { it.substringAfter(": ") }.distinct().take(2).joinToString(" / ")}")
        }
    }
}

/**
 * Turns the player's 48 kHz 16-bit stereo into the format a stereo-only device opened with: [bits]
 * 16, 24 or 32 (the 16-bit sample in the top bytes: lossless), at [rate] (linear interpolation when
 * it isn't 48 kHz, carried across calls so there are no clicks between chunks).
 */
internal class StereoAdapter(val rate: Int, private val bits: Int) {
    private val step = 48_000.0 / rate
    private val bytesPerSample = bits / 8
    private var out = ByteArray(0)
    private var position = 0.0 // of the next output frame, in input frames; -1 = the last frame of the previous call
    private var lastLeft = 0
    private var lastRight = 0

    /** Bytes of [convert]'s last result. */
    var length = 0
        private set

    fun convert(stereo16: ByteArray, frames: Int): ByteArray {
        val maxOut = (frames / step).toInt() + 2
        val need = maxOut * 2 * bytesPerSample
        if (out.size < need) out = ByteArray(need)
        var o = 0
        fun sample(frame: Int, channel: Int): Int =
            if (frame < 0) (if (channel == 0) lastLeft else lastRight)
            else (stereo16[frame * 4 + channel * 2].toInt() and 0xFF) or (stereo16[frame * 4 + channel * 2 + 1].toInt() shl 8)
        fun put(value: Int) {
            for (k in 0 until bytesPerSample - 2) out[o++] = 0
            out[o++] = value.toByte()
            out[o++] = (value shr 8).toByte()
        }
        if (rate == 48_000) {
            for (f in 0 until frames) {
                put(sample(f, 0))
                put(sample(f, 1))
            }
        } else {
            while (position < frames - 1) {
                val i = kotlin.math.floor(position).toInt()
                val t = position - i
                for (c in 0..1) {
                    val a = sample(i, c)
                    val b = sample(i + 1, c)
                    put((a + (b - a) * t).toInt())
                }
                position += step
            }
            position -= frames
        }
        if (frames > 0) {
            lastLeft = sample(frames - 1, 0)
            lastRight = sample(frames - 1, 1)
        }
        length = o
        return out
    }
}

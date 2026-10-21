package io.github.l6desk.recordings

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The recordings on the L6max's card at [root] (in file transfer mode): `RECORDER/<yymmdd_hhmmss>/`.
 * Only ever read: nothing on the card is changed or deleted.
 */
class RecorderCard(val root: File) {
    private val recorder = File(root, "RECORDER")

    fun dir(recording: Recording) = File(recorder, recording.folder)

    /** The recordings, newest first (their folder names are when they started). */
    fun list(): List<Recording> =
        recorder.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
            .sortedByDescending { it.name }
            .map(::read)

    private fun read(dir: File): Recording {
        val all = dir.listFiles { f -> f.isFile && !f.name.startsWith(".") }.orEmpty()
        val files = all.mapNotNull { RecordedFile.parse(it.name, it.length()) }
            .sortedWith(compareBy({ if (it.channel == 0) 9 else it.channel }, { it.take }))
        val duration = files.firstOrNull { it.channel > 0 }?.let { f -> runCatching { WavFile.open(File(dir, f.name)).durationMs }.getOrNull() }
            ?: files.firstOrNull()?.let { f -> runCatching { WavFile.open(File(dir, f.name)).durationMs }.getOrNull() }
        return Recording(dir.name, duration, files, all.sumOf { it.length() })
    }

    /** [recording] with each file's peak found from samples spread through it (quick: about 4 MB read per file). */
    fun measure(recording: Recording): Recording = recording.copy(
        files = recording.files.map { f ->
            if (f.peakDb != null) f else f.copy(peakDb = runCatching { WavFile.open(File(dir(recording), f.name)).sampledPeak() }.getOrNull()?.let(::toDb))
        },
    )

    companion object {
        fun toDb(peak: Float): Double = if (peak <= 0f) Double.NEGATIVE_INFINITY else 20 * log10(peak.toDouble())

        /** Whether [dir] is the root of an L6max card with recordings. */
        fun isCard(dir: File): Boolean = File(dir, "RECORDER").isDirectory && File(dir, "ZOOM_L6max.sys").isFile
    }
}

/**
 * A WAV file's layout: its format and where the audio is. The L6max writes 32-bit float with a
 * `PAD ` chunk before the audio in tracks, and a plain 44-byte header in bounced masters.
 */
class WavFile private constructor(
    val file: File,
    val formatTag: Int,
    val channels: Int,
    val sampleRate: Int,
    val bits: Int,
    val dataOffset: Long,
    val dataLength: Long,
) {
    val isFloat32: Boolean get() = (formatTag == 3 || formatTag == 0xFFFE) && bits == 32
    val frameBytes: Int get() = channels * bits / 8
    val durationMs: Long get() = if (frameBytes == 0 || sampleRate == 0) 0 else dataLength / frameBytes * 1000 / sampleRate

    /** The loudest sample in about [blocks] stretches of [blockBytes] spread through the audio. */
    fun sampledPeak(blocks: Int = 60, blockBytes: Int = 64 * 1024): Float {
        require(isFloat32) { "Not a 32-bit float WAV" }
        var peak = 0f
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(blockBytes)
            val span = maxOf(0L, dataLength - blockBytes)
            for (i in 0 until blocks) {
                val at = dataOffset + (span * i / maxOf(1, blocks - 1)) / 4 * 4
                raf.seek(at)
                val n = raf.read(buf, 0, minOf(blockBytes.toLong(), dataLength).toInt())
                if (n <= 0) break
                peak = maxOf(peak, peakOf(buf, n))
            }
        }
        return peak
    }

    companion object {
        fun open(file: File): WavFile = RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(12)
            raf.readFully(head)
            require(String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WAVE") { "${file.name} isn't a WAV file" }
            var fmt: IntArray? = null
            var pos = 12L
            val chunk = ByteArray(8)
            while (pos + 8 <= raf.length()) {
                raf.seek(pos)
                raf.readFully(chunk)
                val id = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (id == "fmt ") {
                    val b = ByteArray(16)
                    raf.readFully(b)
                    val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                    val tag = bb.short.toInt() and 0xFFFF
                    val channels = bb.short.toInt()
                    val rate = bb.int
                    bb.int // bytes per second
                    bb.short // block align
                    fmt = intArrayOf(tag, channels, rate, 0, 0, bb.short.toInt())
                } else if (id == "data") {
                    val f = fmt ?: error("${file.name} has no format chunk")
                    val length = minOf(size, raf.length() - pos - 8)
                    return WavFile(file, f[0], f[1], f[2], f[5], pos + 8, length)
                }
                pos += 8 + size + (size and 1)
            }
            error("${file.name} has no audio")
        }

        internal fun peakOf(buf: ByteArray, n: Int): Float {
            val fb = ByteBuffer.wrap(buf, 0, n - n % 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            var peak = 0f
            while (fb.hasRemaining()) {
                val v = abs(fb.get())
                if (v > peak && !v.isNaN()) peak = v
            }
            return peak
        }
    }
}

/** Where [RecordingCopier.write] puts a file's bytes: a file, or the bridge's link to the app. */
fun interface ByteSink {
    suspend fun write(bytes: ByteArray, offset: Int, length: Int)
}

/**
 * Copies a recording's files, as they are or converted (24-bit, normalised), with progress: into
 * a folder ([copy], the desktop), or file by file into any stream ([plan] then [write], the Pi
 * bridge sending them over Wi-Fi). Converted files are made on the way, never stored first.
 */
class RecordingCopier(
    private val card: RecorderCard,
    private val bufferBytes: Int = 1 shl 20,
    /**
     * Each file's true peak once found (by path, size and time), so normalising the same recording
     * again needn't read it all first: the Pi keeps one for as long as it runs (that first reading
     * took 43 s for one recording's tracks and master).
     */
    private val peakCache: MutableMap<String, Float> = mutableMapOf(),
) {

    /** One file to send: [name] on the card, [target] its name once downloaded, [bytes] its size then. */
    class Item internal constructor(val name: String, val target: String, val bytes: Long, internal val wav: WavFile?, internal val gain: Float, internal val format: RecordingFormat?)

    /** What a download wrote, and how many samples of each file had to be clipped (24-bit, over full scale). */
    data class Result(val files: List<File>, val clipped: Map<String, Long>)

    /** The files [options] select from [recording] (measured, for USED). */
    fun select(recording: Recording, options: RecordingOptions): List<String> = when (options.selection) {
        RecordingSelection.MASTER -> listOfNotNull(recording.master?.name)
        RecordingSelection.USED -> recording.tracks.filter { it.used }.map { it.name } + listOfNotNull(recording.master?.name)
        RecordingSelection.ALL -> card.dir(recording).listFiles { f -> f.isFile && !f.name.startsWith(".") }.orEmpty().map { it.name }.sorted()
    }

    /** What [name] is called once downloaded with [options]. */
    fun targetName(name: String, options: RecordingOptions): String {
        val parsed = RecordedFile.parse(name, 0) ?: return name
        val notes = listOfNotNull(
            "24-bit".takeIf { options.format == RecordingFormat.PCM24 },
            "normalised".takeIf { options.normalise },
        )
        return if (notes.isEmpty()) parsed.name else parsed.name.substringBeforeLast('.') + " (${notes.joinToString(", ")}).wav"
    }

    /**
     * Works out what downloading [recording] with [options] sends, and each file's size. Normalising
     * reads the files once first to find their peaks ([onBytes] counts that reading).
     */
    suspend fun plan(recording: Recording, options: RecordingOptions, onBytes: (String, Long) -> Unit = { _, _ -> }): List<Item> {
        val dir = card.dir(recording)
        val names = select(recording, options)
        require(names.isNotEmpty()) { if (options.selection == RecordingSelection.MASTER) "This recording has no master" else "Nothing to download" }
        val waves = names.associateWith { n -> if (RecordedFile.parse(n, 0) != null) runCatching { WavFile.open(File(dir, n)) }.getOrNull()?.takeIf { it.isFloat32 } else null }
        val converting = options.format == RecordingFormat.PCM24 || options.normalise

        // Normalising: find the peaks first (the master on its own; the tracks together).
        val peaks = mutableMapOf<String, Float>()
        if (options.normalise) {
            for ((n, w) in waves) {
                if (w == null) continue
                val key = peakKey(w.file)
                peaks[n] = peakCache[key] ?: fullPeak(w) { onBytes(n, it) }.also { peakCache[key] = it }
            }
        }
        fun gainFor(name: String): Float {
            if (!options.normalise) return 1f
            val f = RecordedFile.parse(name, 0) ?: return 1f
            val peak = if (f.channel == 0) peaks[name] ?: 0f else peaks.filterKeys { (RecordedFile.parse(it, 0)?.channel ?: 0) > 0 }.values.maxOrNull() ?: 0f
            // Nothing there (or only noise): leave it, rather than raise hiss by 60 dB.
            if (peak < 10.0.pow(-60.0 / 20).toFloat()) return 1f
            return (10.0.pow(RecordingOptions.NORMALISED_DB / 20) / peak).toFloat()
        }
        return names.map { n ->
            val w = waves[n]
            if (w != null && converting) {
                val bits = if (options.format == RecordingFormat.PCM24) 24 else 32
                Item(n, targetName(n, options), 44 + w.dataLength / 4 * bits / 8, w, gainFor(n), options.format)
            } else {
                Item(n, targetName(n, options), File(dir, n).length(), null, 1f, null)
            }
        }
    }

    /** How many bytes [plan] reads first (for progress): the files whose peaks aren't known yet, if normalised. */
    fun planBytes(recording: Recording, options: RecordingOptions): Long =
        if (!options.normalise) 0
        else select(recording, options).filter { RecordedFile.parse(it, 0) != null }.map { File(card.dir(recording), it) }
            .filter { peakKey(it) !in peakCache }.sumOf { it.length() }

    private fun peakKey(f: File) = "${f.path}|${f.length()}|${f.lastModified()}"

    /**
     * Writes [item] to [out] (exactly [Item.bytes] bytes, or throws); returns how many samples were
     * clipped. [onBytes] counts the source bytes read.
     */
    suspend fun write(recording: Recording, item: Item, out: ByteSink, onBytes: (Long) -> Unit): Long {
        val w = item.wav
        val format = item.format
        return if (w != null && format != null) {
            convert(w, out, format, item.gain, item.bytes, onBytes)
        } else {
            val source = File(card.dir(recording), item.name)
            val copied = plainCopy(source, out, onBytes)
            check(copied == item.bytes) { "Couldn't read all of ${item.name}: is the card still connected?" }
            0
        }
    }

    /** Copies into [destination]: each file beside its final name, renamed when complete. */
    suspend fun copy(recording: Recording, options: RecordingOptions, destination: File, onProgress: (DownloadProgress) -> Unit): Result {
        val dir = card.dir(recording)
        val total = planBytes(recording, options) + select(recording, options).sumOf { File(dir, it).length() }
        var done = 0L
        fun progress(file: String, add: Long) {
            done += add
            onProgress(DownloadProgress(file, done, total))
        }
        val items = plan(recording, options, ::progress)
        destination.mkdirs()
        val written = mutableListOf<File>()
        val clipped = mutableMapOf<String, Long>()
        for (item in items) {
            val target = File(destination, item.target)
            val part = File(destination, target.name + ".part")
            try {
                part.outputStream().buffered(bufferBytes).use { out ->
                    write(recording, item, { b, o, n -> out.write(b, o, n) }) { progress(item.name, it) }.takeIf { it > 0 }?.let { clipped[item.name] = it }
                }
                check(part.length() == item.bytes) { "Couldn't save all of ${item.target}" }
                if (target.exists()) target.delete()
                check(part.renameTo(target)) { "Couldn't save ${target.name}" }
                written += target
            } finally {
                part.delete()
            }
        }
        return Result(written, clipped)
    }

    private suspend fun plainCopy(source: File, out: ByteSink, onBytes: (Long) -> Unit): Long {
        val buf = ByteArray(bufferBytes)
        var total = 0L
        source.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
                onBytes(n.toLong())
            }
        }
        return total
    }

    private suspend fun fullPeak(w: WavFile, onBytes: (Long) -> Unit): Float {
        var peak = 0f
        readAudio(w) { buf, n ->
            peak = maxOf(peak, WavFile.peakOf(buf, n))
            onBytes(n.toLong())
        }
        onBytes(w.file.length() - w.dataLength) // the header, so the total adds up
        return peak
    }

    private suspend fun readAudio(w: WavFile, block: suspend (ByteArray, Int) -> Unit) {
        val buf = ByteArray(bufferBytes)
        RandomAccessFile(w.file, "r").use { raf ->
            raf.seek(w.dataOffset)
            var left = w.dataLength
            while (left > 0) {
                currentCoroutineContext().ensureActive()
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                val whole = n - n % 4
                if (whole < n) raf.seek(raf.filePointer - (n - whole))
                block(buf, whole)
                left -= whole
            }
        }
    }

    /**
     * Writes [w]'s audio × [gain] to [out] as 32-bit float or 24-bit PCM. A float recording can go
     * over full scale (the L6max's vocal track did, by 13 dB); 24-bit can't hold that, so those
     * samples are clipped: returns how many.
     */
    private suspend fun convert(w: WavFile, out: ByteSink, format: RecordingFormat, gain: Float, expected: Long, onBytes: (Long) -> Unit): Long {
        var clipped = 0L
        val outBits = if (format == RecordingFormat.PCM24) 24 else 32
        val dataBytes = w.dataLength / 4 * outBits / 8
        val header = wavHeader(format, w.channels, w.sampleRate, dataBytes)
        out.write(header, 0, header.size)
        var written = 44L
        val outBuf = ByteArray(bufferBytes / 4 * (outBits / 8))
        readAudio(w) { buf, n ->
            val fb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            var o = 0
            while (fb.hasRemaining()) {
                val v = fb.get() * gain
                if (outBits == 24) {
                    if (v > 1f || v < -1f) clipped++
                    val s = (v.coerceIn(-1f, 1f) * 8_388_607f).roundToInt()
                    outBuf[o] = s.toByte(); outBuf[o + 1] = (s shr 8).toByte(); outBuf[o + 2] = (s shr 16).toByte()
                    o += 3
                } else {
                    val bits = java.lang.Float.floatToRawIntBits(v)
                    outBuf[o] = bits.toByte(); outBuf[o + 1] = (bits shr 8).toByte(); outBuf[o + 2] = (bits shr 16).toByte(); outBuf[o + 3] = (bits shr 24).toByte()
                    o += 4
                }
            }
            out.write(outBuf, 0, o)
            written += o
            onBytes(n.toLong())
        }
        onBytes(w.file.length() - w.dataLength)
        check(written == expected) { "Couldn't read all of ${w.file.name}: is the card still connected?" }
        return clipped
    }

    companion object {
        /** A plain 44-byte header, as the L6max writes for a bounce. */
        fun wavHeader(format: RecordingFormat, channels: Int, rate: Int, dataBytes: Long): ByteArray {
            val bits = if (format == RecordingFormat.PCM24) 24 else 32
            val block = channels * bits / 8
            return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt()); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16)
                putShort((if (format == RecordingFormat.PCM24) 1 else 3).toShort()); putShort(channels.toShort())
                putInt(rate); putInt(rate * block); putShort(block.toShort()); putShort(bits.toShort())
                put("data".toByteArray()); putInt(dataBytes.toInt())
            }.array()
        }
    }
}

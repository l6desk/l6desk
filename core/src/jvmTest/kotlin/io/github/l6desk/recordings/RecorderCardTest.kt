package io.github.l6desk.recordings

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RecorderCard and RecordingCopier on a fake card laid out like the real L6max's (docs/hardware-notes.md). */
class RecorderCardTest {
    private val root: File = Files.createTempDirectory("zoom-sd").toFile()
    private val out: File = Files.createTempDirectory("recordings").toFile()
    private val card = RecorderCard(root)
    private val frames = 4_800 // 0.1 s

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
        out.deleteRecursively()
    }

    /** A 32-bit float WAV: with the mixer's 858-byte `PAD ` chunk (tracks), or a plain header (bounces). */
    private fun wav(file: File, channels: Int, level: Float, pad: Boolean = true) {
        file.parentFile.mkdirs()
        val data = ByteBuffer.allocate(frames * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { i -> repeat(channels) { data.putFloat(if (i % 2 == 0) level else -level / 2) } }
        val padChunk = if (pad) 8 + 858 else 0
        val h = ByteBuffer.allocate(36 + padChunk + 8).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + padChunk + data.capacity()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(3); h.putShort(channels.toShort()); h.putInt(48_000)
        h.putInt(48_000 * channels * 4); h.putShort((channels * 4).toShort()); h.putShort(32)
        if (pad) { h.put("PAD ".toByteArray()); h.putInt(858); h.put(ByteArray(858)) }
        h.put("data".toByteArray()); h.putInt(data.capacity())
        file.writeBytes(h.array() + data.array())
    }

    /** Like the test recording: a vocal on CH1, backing on CH5, the rest near-silent; a recorded master and a bounce. */
    private fun recording(folder: String = "261016_104237", bounce: Boolean = true) {
        val dir = File(root, "RECORDER/$folder")
        File(root, "ZOOM_L6max.sys").writeText("ZOOM")
        for (ch in 1..8) {
            val level = when (ch) { 1 -> 0.5f; 5 -> 0.25f; else -> 0.001f } // −6, −12, −60 dBFS
            wav(File(dir, if (ch <= 4) "TRACK0${ch}_01.wav" else "TRACK0${ch}_ST_01.wav"), if (ch <= 4) 1 else 2, level)
        }
        wav(File(dir, "MASTER_01.wav"), 2, 0.02f)
        if (bounce) wav(File(dir, "MASTER_02.wav"), 2, 0.07f, pad = false)
        File(dir, "L6MAXPRJDATA.prj").writeBytes(ByteArray(3388) { 0x40 })
    }

    private fun samples(file: File): FloatArray {
        val w = WavFile.open(file)
        val bytes = file.readBytes().copyOfRange(w.dataOffset.toInt(), (w.dataOffset + w.dataLength).toInt())
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return if (w.bits == 24) FloatArray(bytes.size / 3) { i -> ((bytes[i * 3].toInt() and 0xFF) or ((bytes[i * 3 + 1].toInt() and 0xFF) shl 8) or (bytes[i * 3 + 2].toInt() shl 16)) / 8_388_607f }
        else FloatArray(bytes.size / 4) { bb.getFloat(it * 4) }
    }

    @Test
    fun listsRecordingsNewestFirstAndReadsTheirFiles() {
        recording("261009_200000", bounce = false)
        recording("261016_104237")
        val list = card.list()
        assertEquals(listOf("261016_104237", "261009_200000"), list.map { it.folder })
        val r = list.first()
        assertEquals("16 Oct 2026, 10:42", r.title)
        assertEquals(100, r.durationMs)
        assertEquals(2, r.masterTakes)
        assertEquals("MASTER_02.wav", r.master?.name, "the newest take: the last bounce")
        assertEquals((1..8).toList(), r.tracks.map { it.channel })
        assertEquals(listOf(false, false, false, false, true, true, true, true), r.tracks.map { it.stereo })
        assertEquals("MASTER_01.wav", list[1].master?.name)
    }

    @Test
    fun measuringFindsTheUsedTracks() {
        recording()
        val r = card.measure(card.list().single())
        assertEquals(listOf(1, 5), r.tracks.filter { it.used }.map { it.channel })
        assertEquals(-6.0, r.tracks[0].peakDb ?: 0.0, 0.1)
        assertEquals(-60.0, r.tracks[1].peakDb ?: 0.0, 0.1)
    }

    @Test
    fun theMasterIsCopiedUnchangedByDefault() = runBlocking {
        recording()
        val r = card.list().single()
        val progress = mutableListOf<DownloadProgress>()
        val files = RecordingCopier(card).copy(r, RecordingOptions(), out) { progress += it }.files
        assertEquals(listOf("MASTER_02.wav"), files.map { it.name })
        assertTrue(File(root, "RECORDER/261016_104237/MASTER_02.wav").readBytes().contentEquals(files.single().readBytes()))
        assertEquals(1f, progress.last().fraction)
        assertEquals(emptyList(), out.list()?.filter { it.endsWith(".part") })
    }

    @Test
    fun usedTracksAndAll() = runBlocking {
        recording()
        val copier = RecordingCopier(card)
        val r = card.measure(card.list().single())
        assertEquals(listOf("TRACK01_01.wav", "TRACK05_ST_01.wav", "MASTER_02.wav"), copier.select(r, RecordingOptions(RecordingSelection.USED)))
        val all = copier.copy(r, RecordingOptions(RecordingSelection.ALL), out) {}.files
        assertEquals(11, all.size, "8 tracks, 2 master takes and the project file")
        assertTrue(File(out, "L6MAXPRJDATA.prj").isFile)
    }

    @Test
    fun convertsTo24BitReadableByJavaSound() = runBlocking {
        recording()
        val r = card.list().single()
        val file = RecordingCopier(card).copy(r, RecordingOptions(format = RecordingFormat.PCM24), out) {}.files.single()
        assertEquals("MASTER_02 (24-bit).wav", file.name)
        val format = AudioSystem.getAudioFileFormat(file).format
        assertEquals(24, format.sampleSizeInBits)
        assertEquals(2, format.channels)
        assertEquals(48_000f, format.sampleRate)
        assertEquals(44L + frames * 2 * 3, file.length())
        assertEquals(0.07f, samples(file).first(), 1e-5f)
    }

    @Test
    fun normalisingRaisesTheMasterToMinusOneAndTheTracksTogether() = runBlocking {
        recording()
        val r = card.measure(card.list().single())
        val files = RecordingCopier(card).copy(r, RecordingOptions(RecordingSelection.USED, normalise = true), out) {}.files
        assertEquals(listOf("TRACK01_01 (normalised).wav", "TRACK05_ST_01 (normalised).wav", "MASTER_02 (normalised).wav"), files.map { it.name })
        val target = 0.891f // −1 dBFS
        val peak = { f: File -> samples(f).maxOf { abs(it) } }
        assertEquals(target, peak(files[2]), 1e-3f, "the master on its own")
        assertEquals(target, peak(files[0]), 1e-3f, "the loudest track")
        assertEquals(target / 2, peak(files[1]), 1e-3f, "the other track keeps its balance (6 dB below)")
    }

    @Test
    fun nearSilenceIsntRaised() = runBlocking {
        recording()
        val dir = File(root, "RECORDER/261016_104237")
        wav(File(dir, "MASTER_03.wav"), 2, 0.0001f, pad = false) // −80 dBFS
        val file = RecordingCopier(card).copy(card.list().single(), RecordingOptions(normalise = true), out) {}.files.single()
        assertEquals(0.0001f, samples(file).maxOf { abs(it) }, 1e-6f)
    }

    @Test
    fun aCancelledDownloadLeavesNoFile() = runBlocking {
        recording()
        val r = card.list().single()
        var job: Job? = null
        job = launch {
            RecordingCopier(card, bufferBytes = 4096).copy(r, RecordingOptions(RecordingSelection.ALL), out) { if (it.done > 20_000) job?.cancel() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(emptyList(), out.walk().filter { it.isFile && it.name.endsWith(".part") }.toList())
    }

    @Test
    fun noMasterSaysSo() = runBlocking {
        File(root, "RECORDER/261016_104237").mkdirs()
        val r = card.list().single()
        assertNull(r.master)
        val e = assertFailsWith<IllegalArgumentException> { RecordingCopier(card).copy(r, RecordingOptions(), out) {} }
        assertEquals("This recording has no master", e.message)
        assertFalse(File(out, "MASTER_01.wav").exists())
    }

    @Test
    fun namesAndTitles() {
        assertEquals(RecordedFile("TRACK05_ST_01.wav", 5, true, 1, 9), RecordedFile.parse("TRACK05_ST_01.wav", 9))
        assertEquals(RecordedFile("MASTER_02.wav", 0, true, 2, 9), RecordedFile.parse("MASTER_02.wav", 9))
        assertNull(RecordedFile.parse("L6MAXPRJDATA.prj", 9))
        assertEquals("my folder", Recording.titleOf("my folder"))
        assertEquals("1 Jan 2027, 09:05", Recording.titleOf("270101_090500"))
    }

    @Test
    fun overFullScaleIsClippedIn24BitUnlessNormalised() = runBlocking {
        // The real vocal track went 13 dB over full scale (float keeps it).
        recording()
        val dir = File(root, "RECORDER/261016_104237")
        wav(File(dir, "TRACK01_01.wav"), 1, 4.4f)
        val r = card.measure(card.list().single())
        val copier = RecordingCopier(card)
        val clipped = copier.copy(r, RecordingOptions(RecordingSelection.USED, RecordingFormat.PCM24), out) {}
        assertEquals(setOf("TRACK01_01.wav"), clipped.clipped.keys)
        assertEquals(frames.toLong(), clipped.clipped["TRACK01_01.wav"], "every sample: 4.4 and −2.2 alternately")
        val whole = copier.copy(r, RecordingOptions(RecordingSelection.USED, RecordingFormat.PCM24, normalise = true), out) {}
        assertEquals(emptyMap(), whole.clipped)
        assertEquals(0.891f, samples(whole.files.first()).maxOf { abs(it) }, 1e-3f)
        assertEquals(emptyMap(), copier.copy(r, RecordingOptions(RecordingSelection.USED), out) {}.clipped, "float keeps it")
    }

    @Test
    fun peaksAreRememberedSoNormalisingAgainReadsNothingFirst() = runBlocking {
        recording()
        val r = card.measure(card.list().single())
        val cache = mutableMapOf<String, Float>()
        val copier = RecordingCopier(card, peakCache = cache)
        val options = RecordingOptions(RecordingSelection.USED, normalise = true)
        assertTrue(copier.planBytes(r, options) > 0)
        var read = 0L
        val first = copier.plan(r, options) { _, n -> read += n }
        assertEquals(copier.planBytes(RecorderCard(root).list().single().let { card.measure(it) }, options), 0L, "all known now")
        assertTrue(read > 0)
        read = 0
        val again = copier.plan(r, options) { _, n -> read += n }
        assertEquals(0L, read, "nothing read the second time")
        assertEquals(first.map { it.bytes }, again.map { it.bytes })
    }
}

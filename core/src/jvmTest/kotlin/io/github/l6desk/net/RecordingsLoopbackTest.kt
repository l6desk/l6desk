package io.github.l6desk.net

import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.recordings.BridgeRecordingsSupport
import io.github.l6desk.recordings.DownloadProgress
import io.github.l6desk.recordings.MountedRecordings
import io.github.l6desk.recordings.RecorderCard
import io.github.l6desk.recordings.RecordingCopier
import io.github.l6desk.recordings.RecordingFormat
import io.github.l6desk.recordings.RecordingOptions
import io.github.l6desk.recordings.RecordingSaver
import io.github.l6desk.recordings.RecordingSelection
import io.github.l6desk.recordings.SavedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** RECORDINGS through the bridge over real TCP on localhost (protocol v8). */
class RecordingsLoopbackTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val root: File = Files.createTempDirectory("zoom-sd").toFile()
    private val local: File = Files.createTempDirectory("local-copy").toFile()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        local.deleteRecursively()
    }

    private suspend fun eventually(what: String, check: () -> Boolean) {
        try {
            withTimeout(10.seconds) { while (!check()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for: $what")
        }
    }

    /** Saves into memory; records what was finished and abandoned. */
    private class MemorySaver : RecordingSaver {
        val finished: MutableMap<String, ByteArray> = Collections.synchronizedMap(mutableMapOf())
        val aborted: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override val location = "memory"
        override fun create(folder: String, name: String, bytes: Long) = object : SavedFile {
            val out = ByteArrayOutputStream()
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun finish() { finished["$folder/$name"] = out.toByteArray() }
            override fun abort() { aborted += "$folder/$name" }
        }
    }

    /** A float WAV with an odd length, so files end part-way through a data frame. */
    private fun wav(file: File, channels: Int, frames: Int, level: Float, pad: Boolean = true) {
        file.parentFile.mkdirs()
        val data = ByteBuffer.allocate(frames * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * channels) { i -> data.putFloat(level * ((i % 97) - 48) / 48f) }
        val padChunk = if (pad) 8 + 858 else 0
        val h = ByteBuffer.allocate(36 + padChunk + 8).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + padChunk + data.capacity()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(3); h.putShort(channels.toShort()); h.putInt(48_000)
        h.putInt(48_000 * channels * 4); h.putShort((channels * 4).toShort()); h.putShort(32)
        if (pad) { h.put("PAD ".toByteArray()); h.putInt(858); h.put(ByteArray(858)) }
        h.put("data".toByteArray()); h.putInt(data.capacity())
        file.writeBytes(h.array() + data.array())
    }

    private fun card() {
        File(root, "ZOOM_L6max.sys").writeText("ZOOM")
        val dir = File(root, "RECORDER/261016_104237")
        for (ch in 1..8) wav(File(dir, if (ch <= 4) "TRACK0${ch}_01.wav" else "TRACK0${ch}_ST_01.wav"), if (ch <= 4) 1 else 2, 30_011, if (ch == 1) 3f else if (ch == 5) 0.3f else 0.0005f)
        wav(File(dir, "MASTER_01.wav"), 2, 30_011, 0.02f)
        wav(File(dir, "MASTER_02.wav"), 2, 30_011, 0.07f, pad = false)
        File(dir, "L6MAXPRJDATA.prj").writeBytes(ByteArray(3388) { 0x40 })
    }

    private suspend fun connected(host: MountedRecordings?): NetworkMidiTransport {
        val mixer = SimulatorTransport().apply { open(SimulatorTransport.DEVICE) }
        val bridge = BridgeServer(mixer, scope, "test-pi", 1.seconds, 5.seconds, recordings = host).also { it.start(0) }
        eventually("bridge running") { bridge.status.value.running }
        val tablet = NetworkMidiTransport(scope, JavaSocketConnector, "Test tablet", 1.seconds, 5.seconds, retryDelay = 150.milliseconds)
        tablet.setBridges(listOf(BridgeAddress("127.0.0.1", bridge.status.value.port)))
        tablet.open(tablet.devices.value.single())
        eventually("connected") { tablet.state.value is ConnectionState.Connected }
        return tablet
    }

    @Test
    fun theRecordingsAreListedAndDownloadedExactlyAsTheDesktopWould() = runBlocking<Unit> {
        card()
        val host = MountedRecordings(root, pollMs = 200).also { it.start(scope) }
        val tablet = connected(host)
        eventually("listed and measured") { tablet.recordings.value?.singleOrNull()?.tracks?.all { it.peakDb != null } == true }
        val recording = tablet.recordings.value?.single() ?: error("none")
        assertEquals(listOf(1, 5), recording.tracks.filter { it.used }.map { it.channel })

        val saver = MemorySaver()
        val support = BridgeRecordingsSupport(tablet, saver)
        val copier = RecordingCopier(RecorderCard(root))
        for (options in listOf(
            RecordingOptions(),
            RecordingOptions(RecordingSelection.USED, RecordingFormat.PCM24),
            RecordingOptions(RecordingSelection.ALL, RecordingFormat.PCM24, normalise = true),
        )) {
            saver.finished.clear()
            val progress = mutableListOf<DownloadProgress>()
            val message = support.download(recording, options) { progress += it }
            val expected = copier.copy(recording, options, File(local, options.toString())) {}
            assertEquals(expected.files.map { "261016_104237/${it.name}" }.toSet(), saver.finished.keys, "$options")
            for (f in expected.files) assertContentEquals(f.readBytes(), saver.finished["261016_104237/${f.name}"], "${f.name} with $options")
            assertEquals(1f, progress.last().fraction)
            if (options.selection == RecordingSelection.USED) {
                assertTrue("TRACK01_01 (24-bit).wav went over full scale" in message, message)
            } else {
                assertTrue("over full scale" !in message, message)
            }
        }
        assertEquals(emptyList(), saver.aborted)
    }

    @Test
    fun aCancelledDownloadIsAbandonedAndTheNextOneWorks() = runBlocking<Unit> {
        card()
        val host = MountedRecordings(root, pollMs = 200).also { it.start(scope) }
        val tablet = connected(host)
        eventually("listed") { tablet.recordings.value?.isNotEmpty() == true }
        val recording = tablet.recordings.value?.single() ?: error("none")
        val saver = MemorySaver()
        val all = RecordingOptions(RecordingSelection.ALL)
        // Cancelled from inside once 100 KB is in: over localhost the rest would otherwise often
        // arrive before a cancel from the test's thread (seen on a slow Windows runner).
        val self = kotlinx.coroutines.CompletableDeferred<kotlinx.coroutines.Job>()
        val job = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            tablet.downloadRecording(recording, all, saver) { if (it.done > 100_000) self.getCompleted().cancel() }
        }
        self.complete(job)
        withTimeout(10.seconds) { job.join() }
        assertTrue(job.isCancelled)
        assertEquals(1, saver.aborted.size, "the file being written is abandoned")
        val result = tablet.downloadRecording(recording, RecordingOptions(), saver) {}
        assertEquals(listOf("MASTER_02.wav"), result.files)
        assertContentEquals(File(root, "RECORDER/261016_104237/MASTER_02.wav").readBytes(), saver.finished["261016_104237/MASTER_02.wav"])
    }

    @Test
    fun noCardAndNoSuchRecording() = runBlocking<Unit> {
        val host = MountedRecordings(root, pollMs = 200).also { it.start(scope) }
        val tablet = connected(host)
        eventually("offered") { tablet.recordingsOffered.value }
        assertEquals(null, tablet.recordings.value)
        card()
        eventually("card appears") { tablet.recordings.value?.isNotEmpty() == true }
        val gone = tablet.recordings.value?.single()?.copy(folder = "261001_000000") ?: error("none")
        val e = assertFailsWith<IllegalStateException> { tablet.downloadRecording(gone, RecordingOptions(), MemorySaver()) {} }
        assertEquals("That recording isn't on the card any more", e.message)
    }

    @Test
    fun aBridgeWithoutTheCardDoesntOfferRecordings() = runBlocking<Unit> {
        val tablet = connected(null)
        delay(500)
        assertEquals(false, tablet.recordingsOffered.value)
    }

    @Test
    fun aLargeFileGoesAtLocalSpeed() = runBlocking<Unit> {
        File(root, "ZOOM_L6max.sys").writeText("ZOOM")
        wav(File(root, "RECORDER/261016_104237/MASTER_02.wav"), 2, 48_000 * 60, 0.1f, pad = false) // 1 minute: 23 MB
        val host = MountedRecordings(root, pollMs = 200).also { it.start(scope) }
        val tablet = connected(host)
        eventually("listed") { tablet.recordings.value?.isNotEmpty() == true }
        val saver = MemorySaver()
        val start = System.nanoTime()
        val result = tablet.downloadRecording(tablet.recordings.value?.single() ?: error("none"), RecordingOptions(), saver) {}
        val seconds = (System.nanoTime() - start) / 1e9
        println("Downloaded ${result.bytes / 1_000_000} MB in ${"%.2f".format(seconds)} s: ${"%.1f".format(result.bytes / 1e6 / seconds)} MB/s")
        assertTrue(seconds < 10, "took $seconds s")
    }

    @Test
    fun filesTheSaverCantKeepAreSkippedAndTheRestArrive() = runBlocking<Unit> {
        card()
        val host = MountedRecordings(root, pollMs = 200).also { it.start(scope) }
        val tablet = connected(host)
        eventually("listed") { tablet.recordings.value?.isNotEmpty() == true }
        val inner = MemorySaver()
        val wavsOnly = object : RecordingSaver by inner {
            override fun create(folder: String, name: String, bytes: Long) = if (name.endsWith(".wav")) inner.create(folder, name, bytes) else null
        }
        val recording = tablet.recordings.value?.single() ?: error("none")
        val result = tablet.downloadRecording(recording, RecordingOptions(RecordingSelection.ALL), wavsOnly) {}
        assertEquals(listOf("L6MAXPRJDATA.prj"), result.skipped)
        assertEquals(10, result.files.size)
        for (name in result.files) assertContentEquals(File(root, "RECORDER/261016_104237/$name").readBytes(), inner.finished["261016_104237/$name"], name)
    }
}

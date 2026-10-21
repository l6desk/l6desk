package io.github.l6desk.net

import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.MidiMessage
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.Control
import io.github.l6desk.model.Profile
import io.github.l6desk.player.MusicPlayer
import io.github.l6desk.player.PlayerCommand
import io.github.l6desk.player.PlayerState
import io.github.l6desk.player.Track
import io.github.l6desk.player.UploadReceiver
import io.github.l6desk.player.UploadSource
import io.github.l6desk.player.UploadStage
import io.github.l6desk.player.UploadStart
import io.github.l6desk.player.UploadStatus
import io.github.l6desk.player.WavPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Collections
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Uploads over real TCP on localhost: NetworkMidiTransport.upload → BridgeServer → the player. */
class UploadLoopbackTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir: File = Files.createTempDirectory("l6desk-upload").toFile()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private suspend fun eventually(what: String, check: () -> Boolean) {
        try {
            withTimeout(10.seconds) { while (!check()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for: $what")
        }
    }

    private class BytesSource(private val bytes: ByteArray, override val name: String) : UploadSource {
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

    /** Takes uploads slowly (like an SD card), recording what arrived. */
    private class SlowPlayer(private val msPerWrite: Long) : MusicPlayer {
        override val tracks = MutableStateFlow(emptyList<Track>())
        override val state = MutableStateFlow(PlayerState())
        override fun handle(command: PlayerCommand) = Unit
        val received = ByteArrayOutputStream()
        @Volatile var aborted = false
        @Volatile var finished = false
        override fun startUpload(start: UploadStart, onStatus: (UploadStatus) -> Unit) = object : UploadReceiver {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                Thread.sleep(msPerWrite)
                synchronized(received) { received.write(bytes, offset, length) }
            }
            override fun finish() {
                finished = true
                onStatus(UploadStatus(start.id, UploadStage.DONE, file = "${start.playlist}/01 ${start.name}"))
            }
            override fun abort() { aborted = true }
        }
    }

    private suspend fun connected(player: MusicPlayer, mixer: SimulatorTransport = SimulatorTransport()): Pair<BridgeServer, NetworkMidiTransport> {
        mixer.open(SimulatorTransport.DEVICE)
        val bridge = BridgeServer(mixer, scope, "test-bridge", 100.milliseconds, 400.milliseconds, player).also { it.start(0) }
        eventually("bridge running") { bridge.status.value.running }
        val tablet = NetworkMidiTransport(scope, JavaSocketConnector, "Test tablet", 100.milliseconds, 400.milliseconds, retryDelay = 150.milliseconds)
        tablet.setBridges(listOf(BridgeAddress("127.0.0.1", bridge.status.value.port)))
        tablet.open(tablet.devices.value.single())
        eventually("connected with the player") { tablet.state.value is ConnectionState.Connected && tablet.player.value != null }
        return bridge to tablet
    }

    private fun wavBytes(seconds: Int): ByteArray {
        val frames = 48_000 * seconds
        val pcm = ByteArray(frames * 4) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(pcm), AudioFormat(48_000f, 16, 2, true, false), frames.toLong()), AudioFileFormat.Type.WAVE, out)
        return out.toByteArray()
    }

    @Test
    fun aSongIsUploadedIntoAPlaylistAndAppearsInTheApp() = runBlocking<Unit> {
        val player = WavPlayer(dir, scope, { error("no playback here") }, { true }, rescanInterval = 200.milliseconds, converter = null, minFreeBytes = 0)
        val (_, tablet) = connected(player)
        assertTrue(tablet.player.value?.canUpload == true)
        val bytes = wavBytes(3) // ~576 KB: many frames
        val statuses = Collections.synchronizedList(mutableListOf<UploadStatus>())
        val result = tablet.upload(BytesSource(bytes, "02 - Yesterday.wav"), "The Beatles") { statuses += it }
        assertEquals(UploadStage.DONE, result.stage, result.message)
        assertEquals("The Beatles/01 Yesterday.wav", result.file)
        assertContentEquals(bytes, File(dir, "The Beatles/01 Yesterday.wav").readBytes(), "stored unchanged (already 48 kHz)")
        // Progress arrives through a StateFlow, so some reports may be skipped, but it only goes up.
        val received = statuses.filter { it.stage == UploadStage.RECEIVING }.map { it.received }
        assertTrue(received.size >= 3 && received == received.sorted() && received.last() <= bytes.size, "progress: $received")
        eventually("the app sees the song") { tablet.player.value?.tracks?.any { it.title == "Yesterday" && it.playlist == "The Beatles" } == true }
        assertEquals(listOf("The Beatles"), tablet.player.value?.state?.playlists)
    }

    @Test
    fun midiGoesAheadOfUploadData() = runBlocking<Unit> {
        val mixer = SimulatorTransport()
        val player = SlowPlayer(msPerWrite = 15) // ~2 MB/s, like a Pi on 2.4 GHz Wi-Fi
        val (_, tablet) = connected(player, mixer)
        val upload = async { tablet.upload(BytesSource(ByteArray(3_000_000) { it.toByte() }, "big.wav"), "Set") }
        eventually("upload under way") { synchronized(player.received) { player.received.size() } > 200_000 }
        val level = Control.Channel(1, ChannelParam.LEVEL)
        val cc = requireNotNull(Profile.FACTORY.ccMap[level])
        val worst = (1..5).maxOf { i ->
            val sent = TimeSource.Monotonic.markNow()
            tablet.send(MidiMessage.ControlChange(1, cc, 10 + i))
            eventually("CC $i reached the mixer") { mixer.valueOf(level) == 10 + i }
            sent.elapsedNow().also { delay(50) }
        }
        assertTrue(worst < 300.milliseconds, "MIDI took $worst behind the upload")
        assertTrue(!upload.isCompleted, "the upload was still going while MIDI got through")
        assertEquals(UploadStage.DONE, upload.await().stage)
        assertEquals(3_000_000, synchronized(player.received) { player.received.size() })
    }

    @Test
    fun cancellingAnUploadTellsTheBridgeToDropIt() = runBlocking<Unit> {
        val player = SlowPlayer(msPerWrite = 20)
        val (_, tablet) = connected(player)
        val upload = scope.async { tablet.upload(BytesSource(ByteArray(2_000_000), "big.wav"), "Set") }
        eventually("upload under way") { synchronized(player.received) { player.received.size() } > 100_000 }
        upload.cancelAndJoin()
        eventually("the bridge dropped it") { player.aborted }
        assertTrue(!player.finished)
        assertTrue(tablet.state.value is ConnectionState.Connected, "the link stays up")
    }

    @Test
    fun losingTheLinkFailsTheUploadAndTheBridgeDropsIt() = runBlocking<Unit> {
        val player = SlowPlayer(msPerWrite = 20)
        val (bridge, tablet) = connected(player)
        val upload = async { tablet.upload(BytesSource(ByteArray(2_000_000), "big.wav"), "Set") }
        eventually("upload under way") { synchronized(player.received) { player.received.size() } > 100_000 }
        bridge.stop()
        val result = withTimeout(5.seconds) { upload.await() }
        assertEquals(UploadStage.FAILED, result.stage)
        assertTrue("lost" in result.message.orEmpty(), result.message)
        eventually("the bridge dropped it") { player.aborted }
    }

    @Test
    fun aRefusedUploadSaysWhy() = runBlocking<Unit> {
        val player = WavPlayer(dir, scope, { error("no playback here") }, { true }, converter = null, minFreeBytes = 0)
        val (_, tablet) = connected(player)
        val result = tablet.upload(BytesSource(ByteArray(10), "notes.txt"), "Set")
        assertEquals(UploadStage.FAILED, result.stage)
        assertTrue("isn't a music file" in result.message.orEmpty(), result.message)
    }
}

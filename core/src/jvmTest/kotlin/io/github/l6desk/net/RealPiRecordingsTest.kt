package io.github.l6desk.net

import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.recordings.RecordingFormat
import io.github.l6desk.recordings.RecordingOptions
import io.github.l6desk.recordings.RecordingSaver
import io.github.l6desk.recordings.RecordingSelection
import io.github.l6desk.recordings.SavedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Downloads the newest recording from the real Pi (L6MAX_PI=host), logging every phase, the gaps
 * between data and any link changes. PIN: L6MAX_PI_PIN. Options: L6MAX_PI_OPTIONS = USED,FLOAT32,true.
 */
class RealPiRecordingsTest {
    @Test
    fun downloadFromThePi() = runBlocking {
        val host = System.getenv("L6MAX_PI") ?: return@runBlocking
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val t0 = System.currentTimeMillis()
        fun log(s: String) = println("%7.2f  %s".format((System.currentTimeMillis() - t0) / 1000.0, s))
        // L6MAX_PI_FOUND=1: find the Pi as the app does (discovery), with the simulator beside it
        // and the auto-connect remembering the Pi, rather than by its address.
        val found = System.getenv("L6MAX_PI_FOUND") == "1"
        val tablet = NetworkMidiTransport(
            scope, JavaSocketConnector, "Recordings test",
            discovery = if (found) BridgeDiscovery(scope, JavaDatagramChannel::open) else null,
        )
        val foundId = NetworkMidiTransport.FOUND_PREFIX + "L6Desk Bridge"
        if (found) {
            System.getenv("L6MAX_PI_PIN")?.let { tablet.setPins(mapOf(foundId to it)) }
            val multi = io.github.l6desk.midi.MultiTransport(listOf(tablet, io.github.l6desk.midi.SimulatorTransport()), scope)
            scope.launch { multi.state.collect { log("desk: $it") } }
            scope.launch { tablet.devices.collect { d -> log("found: ${d.map { it.id }}") } }
            io.github.l6desk.sync.ConnectionManager(multi, scope, rememberedId = foundId)
        } else {
            tablet.setBridges(listOf(BridgeAddress(host)))
            System.getenv("L6MAX_PI_PIN")?.let { tablet.setPins(mapOf(BridgeAddress(host).id to it)) }
            scope.launch { tablet.state.collect { log("link: $it") } }
            tablet.open(tablet.devices.value.single())
        }
        withTimeout(20.seconds) { while (tablet.state.value !is ConnectionState.Connected) delay(50) }
        withTimeout(60.seconds) { while (tablet.recordings.value?.firstOrNull()?.tracks?.all { it.peakDb != null } != true) delay(200) }
        val r = tablet.recordings.value?.first() ?: error("none")
        log("recording ${r.folder}: ${r.files.size} files")
        val (sel, fmt, norm) = (System.getenv("L6MAX_PI_OPTIONS") ?: "USED,FLOAT32,true").split(",")
        val options = RecordingOptions(RecordingSelection.valueOf(sel), RecordingFormat.valueOf(fmt), norm.toBoolean())
        val out = File(System.getenv("L6MAX_PI_OUT") ?: "build/pi-recordings").apply { deleteRecursively(); mkdirs() }
        val saver = object : RecordingSaver {
            override val location = out.path
            override fun create(folder: String, name: String, bytes: Long): SavedFile {
                log("file $name ($bytes bytes)")
                val o = File(out, name).outputStream().buffered(1 shl 20)
                return object : SavedFile {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) = o.write(bytes, offset, length)
                    override fun finish() { o.close(); log("finished $name") }
                    override fun abort() { o.close(); log("aborted $name") }
                }
            }
        }
        // L6MAX_PI_MIDI=1: a CC on channel 15 (which the L6max, on channel 1, ignores) every 200 ms,
        // as desk moves would send during a download.
        if (System.getenv("L6MAX_PI_MIDI") == "1") scope.launch {
            while (true) {
                delay(200)
                tablet.send(io.github.l6desk.midi.MidiMessage.ControlChange(15, 0, 0))
            }
        }
        var last = System.currentTimeMillis()
        var maxGap = 0L
        var lastDone = 0L
        var lastReport = 0L
        try {
            val result = withContext(Dispatchers.IO) {
                withTimeout(15.minutes) {
                    tablet.downloadRecording(r, options, saver) { p ->
                        val now = System.currentTimeMillis()
                        if (p.done != lastDone) {
                            maxGap = maxOf(maxGap, now - last)
                            last = now
                            lastDone = p.done
                        }
                        if (now - lastReport > 2000) {
                            lastReport = now
                            log("%s %.1f / %.1f MB (largest gap %d ms)".format(p.file, p.done / 1e6, p.total / 1e6, maxGap))
                        }
                    }
                }
            }
            log("DONE ${result.files} ${result.bytes / 1e6} MB, clipped ${result.clipped}, largest gap $maxGap ms")
        } catch (e: Exception) {
            log("FAILED: ${e.message}; ${(System.currentTimeMillis() - last)} ms since the last data; largest gap $maxGap ms")
        } finally {
            tablet.close()
            scope.cancel()
        }
    }
}

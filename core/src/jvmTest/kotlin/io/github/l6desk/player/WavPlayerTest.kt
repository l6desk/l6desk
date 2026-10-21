package io.github.l6desk.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Collections
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class WavPlayerTest {
    private val dir: File = Files.createTempDirectory("l6max-music").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val outputs = mutableListOf<FakeOutput>()
    private var available = true

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    /** Records what's played; [paced] makes writes take real time, so a song lasts a while. */
    private inner class FakeOutput(private val paced: Boolean) : PcmOutput {
        val bytes = java.io.ByteArrayOutputStream()
        @Volatile var closed = false
        override fun write(bytes: ByteArray, length: Int) {
            synchronized(this) { this.bytes.write(bytes, 0, length) }
            if (paced) Thread.sleep(5)
        }
        override fun pause() = Unit
        override fun resume() = Unit
        override val framesPlayed: Long get() = synchronized(this) { bytes.size() / 8L }
        override fun drain() = Unit
        override fun close() { closed = true }
        fun frames(): ShortArray = synchronized(this) {
            val b = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            ShortArray(b.remaining()).also { b.get(it) }
        }
    }

    private fun player(paced: Boolean = false, converter: AudioConverter? = FfmpegConverter.find()) = WavPlayer(
        dir, scope,
        openOutput = { FakeOutput(paced).also { synchronized(outputs) { outputs += it } } },
        outputAvailable = { available },
        rescanInterval = 100.milliseconds,
        converter = converter,
        minFreeBytes = 0,
    )

    /** A WAV of [ms] milliseconds holding constant samples [left] / [right] ([right] null = mono), at [path] in the music folder. */
    private fun wav(path: String, ms: Int = 200, left: Short = 8000, right: Short? = -8000, rate: Float = 44_100f): File {
        val f = File(dir, path).apply { parentFile.mkdirs() }
        f.writeBytes(wavBytes(ms, left, right, rate))
        return f
    }

    private fun wavBytes(ms: Int = 200, left: Short = 8000, right: Short? = -8000, rate: Float = 48_000f): ByteArray {
        val channels = if (right == null) 1 else 2
        val frames = (rate * ms / 1000).toInt()
        val buf = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { buf.putShort(left); if (right != null) buf.putShort(right) }
        val format = AudioFormat(rate, 16, channels, true, false)
        val out = java.io.ByteArrayOutputStream()
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(buf.array()), format, frames.toLong()), AudioFileFormat.Type.WAVE, out)
        return out.toByteArray()
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for: $what")
            Thread.sleep(10)
        }
    }

    private fun near(expected: Int, actual: Int) = abs(expected - actual) <= expected / 50 + 2

    private fun names(playlist: String) = File(dir, playlist).list().orEmpty().filterNot { it.startsWith(".") }.sorted()

    private fun WavPlayer.titles(playlist: String) = tracks.value.filter { it.playlist == playlist }.map { it.title }

    /** Uploads [bytes] as [name] into [playlist]; returns the final status. */
    private fun WavPlayer.upload(playlist: String, name: String, bytes: ByteArray): UploadStatus {
        val statuses = Collections.synchronizedList(mutableListOf<UploadStatus>())
        val r = startUpload(UploadStart(7, playlist, name, bytes.size.toLong())) { statuses += it }
        bytes.toList().chunked(10_000).forEach { r.write(it.toByteArray(), 0, it.size) }
        r.finish()
        eventually("upload of $name finished") { statuses.any { it.stage == UploadStage.DONE || it.stage == UploadStage.FAILED } }
        assertTrue(statuses.any { it.stage == UploadStage.CONVERTING })
        return statuses.last()
    }

    // ---- Library ------------------------------------------------------------------------------

    @Test
    fun playlistsAreFoldersAndTitlesHideTheOrderNumbers() {
        wav("The Beatles/02 Help.wav", ms = 500)
        wav("The Beatles/01 Yesterday.wav")
        wav("Abba/01 SOS.wav")
        File(dir, "Abba/notes.txt").writeText("not a song")
        wav("Abba/.hidden.wav")
        val p = player()
        assertEquals(listOf("Abba", "The Beatles"), p.state.value.playlists)
        assertEquals("Abba", p.state.value.playlist, "the first playlist is shown")
        assertEquals(listOf("SOS"), p.titles("Abba"))
        assertEquals(listOf("Yesterday", "Help"), p.titles("The Beatles"))
        assertEquals("The Beatles/01 Yesterday.wav", p.tracks.value.first { it.title == "Yesterday" }.file)
        assertTrue(near(500, p.tracks.value.first { it.title == "Help" }.durationMs.toInt()))
        assertEquals("Abba/01 SOS.wav", p.state.value.cued)

        p.handle(PlayerCommand.SelectPlaylist("The Beatles"))
        assertEquals("The Beatles", p.state.value.playlist)
        assertEquals("The Beatles/01 Yesterday.wav", p.state.value.cued, "selecting a playlist cues its first song")
        assertEquals("The Beatles", player().state.value.playlist, "kept")

        wav("The Beatles/03 Let It Be.wav")
        eventually("a copied-in song appears") { p.titles("The Beatles").size == 3 }
    }

    @Test
    fun looseFilesMoveIntoSongsAndUnnumberedSongsGoOnTheEnd() {
        wav("Test track.wav")
        wav("Set/01 Opener.wav")
        wav("Set/02 Closer.wav")
        val p = player()
        p.handle(PlayerCommand.SetEnd("Set/02 Closer.wav", TrackEnd.LOOP))
        eventually("moved and numbered") { names(WavPlayer.DEFAULT_PLAYLIST) == listOf("01 Test track.wav") }
        assertFalse(File(dir, "Test track.wav").exists())

        wav("Set/Encore.wav")
        eventually("numbered on the end") { names("Set") == listOf("01 Opener.wav", "02 Closer.wav", "03 Encore.wav") }
        eventually("listed") { p.titles("Set") == listOf("Opener", "Closer", "Encore") }
        assertEquals(TrackEnd.LOOP, p.tracks.value.first { it.title == "Closer" }.end)
    }

    @Test
    fun moveRenumbersTheFilesAndSettingsFollowTheSong() {
        wav("Set/01 A.wav"); wav("Set/02 B.wav"); wav("Set/03 C.wav")
        val p = player()
        p.handle(PlayerCommand.SetEnd("Set/03 C.wav", TrackEnd.NEXT))
        p.handle(PlayerCommand.Cue("Set/03 C.wav"))
        p.handle(PlayerCommand.Move("Set/03 C.wav", -2))
        assertEquals(listOf("01 C.wav", "02 A.wav", "03 B.wav"), names("Set"))
        assertEquals(listOf("C", "A", "B"), p.titles("Set"))
        assertEquals(TrackEnd.NEXT, p.tracks.value.first { it.title == "C" }.end, "the end setting moved with the song")
        assertEquals("Set/01 C.wav", p.state.value.cued, "still cued")

        p.handle(PlayerCommand.Move("Set/01 C.wav", 5)) // past the end: last
        assertEquals(listOf("A", "B", "C"), p.titles("Set"))
        p.handle(PlayerCommand.Move("Set/01 A.wav", -1)) // already first: nothing changes
        assertEquals(listOf("01 A.wav", "02 B.wav", "03 C.wav"), names("Set"))
        assertEquals(TrackEnd.NEXT, player().tracks.value.first { it.title == "C" }.end, "and saved")
    }

    @Test
    fun movingTheSongThatsPlayingKeepsItPlayingAndPlayNextFollowsTheNewOrder() {
        wav("Set/01 Long.wav", ms = 3_000); wav("Set/02 B.wav"); wav("Set/03 C.wav")
        val p = player(paced = true)
        p.handle(PlayerCommand.SetEnd("Set/01 Long.wav", TrackEnd.NEXT))
        p.handle(PlayerCommand.Play("Set/01 Long.wav"))
        eventually("started") { outputs.isNotEmpty() }
        p.handle(PlayerCommand.Move("Set/01 Long.wav", 1)) // B, Long, C
        assertEquals("Set/02 Long.wav", p.state.value.playing)
        eventually("went on to C, the song now after it") { p.state.value.playing == "Set/03 C.wav" }
        p.handle(PlayerCommand.Stop)
    }

    @Test
    fun deleteRemovesTheFileRenumbersAndStopsItIfPlaying() {
        wav("Set/01 A.wav", ms = 3_000); wav("Set/02 B.wav"); wav("Set/03 C.wav")
        val p = player(paced = true)
        p.handle(PlayerCommand.Play("Set/01 A.wav"))
        eventually("started") { outputs.isNotEmpty() }
        p.handle(PlayerCommand.Delete("Set/01 A.wav"))
        assertNull(p.state.value.playing)
        assertTrue(outputs[0].closed)
        assertEquals(listOf("01 B.wav", "02 C.wav"), names("Set"))
        assertEquals(listOf("B", "C"), p.titles("Set"))
        p.handle(PlayerCommand.Delete("Set/nothing.wav")) // unknown: ignored
        assertEquals(2, p.titles("Set").size)
    }

    @Test
    fun newPlaylistIsCreatedAndShown() {
        wav("Set/01 A.wav")
        val p = player()
        p.handle(PlayerCommand.CreatePlaylist("  Saturday / gig  "))
        assertEquals(listOf("Saturday gig", "Set"), p.state.value.playlists)
        assertEquals("Saturday gig", p.state.value.playlist)
        assertNull(p.state.value.cued, "nothing to cue in an empty playlist")
        p.handle(PlayerCommand.CreatePlaylist("..."))
        assertEquals(2, p.state.value.playlists.size, "a name with nothing left is ignored")
    }

    @Test
    fun renamingAPlaylistKeepsItsSongsSettingsAndTheSongPlaying() {
        wav("Set/01 Long.wav", ms = 3_000); wav("Set/02 B.wav"); wav("Other/01 X.wav")
        val p = player(paced = true)
        assertTrue(p.state.value.canEditPlaylists)
        p.handle(PlayerCommand.SelectPlaylist("Set"))
        p.handle(PlayerCommand.SetEnd("Set/02 B.wav", TrackEnd.LOOP))
        p.handle(PlayerCommand.SetEnd("Set/01 Long.wav", TrackEnd.NEXT))
        p.handle(PlayerCommand.Play("Set/01 Long.wav"))
        eventually("started") { outputs.isNotEmpty() }

        if (System.getProperty("os.name").startsWith("Windows")) {
            // Windows won't rename a folder with a file open in it: refused, saying why, and the song plays on.
            p.handle(PlayerCommand.RenamePlaylist("Set", "Saturday gig"))
            assertEquals(listOf("Other", "Set"), p.state.value.playlists)
            assertTrue(p.state.value.error.orEmpty().contains("stop it first"), p.state.value.error)
            assertEquals("Set/01 Long.wav", p.state.value.playing)
            p.handle(PlayerCommand.Stop)
            p.handle(PlayerCommand.RenamePlaylist("Set", "Saturday gig"))
            assertEquals(listOf("Other", "Saturday gig"), p.state.value.playlists, "fine once stopped")
            return
        }
        p.handle(PlayerCommand.RenamePlaylist("Set", "  Saturday / gig "))
        assertEquals(listOf("Other", "Saturday gig"), p.state.value.playlists)
        assertEquals("Saturday gig", p.state.value.playlist, "still the one shown")
        assertEquals(listOf("01 Long.wav", "02 B.wav"), names("Saturday gig"))
        assertFalse(File(dir, "Set").exists())
        assertEquals("Saturday gig/01 Long.wav", p.state.value.playing, "still playing")
        assertEquals(TrackEnd.LOOP, p.tracks.value.first { it.title == "B" }.end, "end settings follow")
        eventually("Play next went on to B, in the renamed playlist") { p.state.value.playing == "Saturday gig/02 B.wav" }
        p.handle(PlayerCommand.Stop)
        assertEquals(TrackEnd.LOOP, player().tracks.value.first { it.title == "B" }.end, "and saved")
    }

    @Test
    fun renamingASongKeepsItsPlaceItsEndSettingAndPlayback() {
        wav("Set/01 Long.wav", ms = 3_000); wav("Set/02 B.wav"); wav("Set/03 C.wav")
        val p = player(paced = true)
        p.handle(PlayerCommand.SetEnd("Set/01 Long.wav", TrackEnd.NEXT))
        p.handle(PlayerCommand.Play("Set/01 Long.wav"))
        eventually("started") { outputs.isNotEmpty() }

        p.handle(PlayerCommand.Rename("Set/01 Long.wav", "  Walk-on / intro  "))
        assertEquals(listOf("01 Walk-on intro.wav", "02 B.wav", "03 C.wav"), names("Set"))
        assertEquals(listOf("Walk-on intro", "B", "C"), p.titles("Set"))
        assertEquals("Set/01 Walk-on intro.wav", p.state.value.playing, "still playing")
        assertEquals(TrackEnd.NEXT, p.tracks.value.first().end, "its end setting followed")
        eventually("Play next carried on to B") { p.state.value.playing == "Set/02 B.wav" }
        p.handle(PlayerCommand.Stop)

        p.handle(PlayerCommand.Rename("Set/03 C.wav", "1999.wav")) // a typed ".wav" isn't doubled; a number-like title is kept
        assertEquals("03 1999.wav", names("Set")[2])
        assertEquals("1999", p.titles("Set")[2])
        p.handle(PlayerCommand.Rename("Set/02 B.wav", " / ")) // nothing left: ignored
        p.handle(PlayerCommand.Rename("Set/nothing.wav", "X")) // unknown: ignored
        assertEquals(listOf("Walk-on intro", "B", "1999"), p.titles("Set"))
        assertEquals(TrackEnd.NEXT, player().tracks.value.first { it.title == "Walk-on intro" }.end, "and saved")
    }

    @Test
    fun renamingTheMomentASongStartsDoesntStopIt() {
        // The playback thread opens the file just after PLAY; a rename in between must not lose it.
        repeat(20) { round ->
            val name = "Round$round"
            wav("$name/01 Song.wav", ms = 2_000)
            val p = player(paced = true)
            // On Windows a playlist (folder) can't be renamed while a song in it plays; the song can.
            val renamesFolder = !System.getProperty("os.name").startsWith("Windows")
            val folder = if (renamesFolder) "$name renamed" else name
            p.handle(PlayerCommand.Play("$name/01 Song.wav"))
            if (renamesFolder) p.handle(PlayerCommand.RenamePlaylist(name, folder))
            p.handle(PlayerCommand.Rename("$folder/01 Song.wav", "Tune"))
            eventually("round $round playing on") { (p.state.value.positionMs > 0) || p.state.value.error != null }
            assertNull(p.state.value.error, "round $round")
            assertEquals("$folder/01 Tune.wav", p.state.value.playing)
            p.handle(PlayerCommand.Stop)
            p.shutdown()
        }
    }

    @Test
    fun aPlaylistCantTakeAnothersNameOrBeRenamedWhileSongsArrive() {
        wav("Set/01 A.wav"); wav("Other/01 X.wav")
        val p = player(converter = null)
        p.handle(PlayerCommand.RenamePlaylist("Set", "other"))
        assertEquals(listOf("Other", "Set"), p.state.value.playlists)
        assertEquals("There's already a playlist called other.", p.state.value.error)
        p.handle(PlayerCommand.RenamePlaylist("Set", "SET")) // only the case changes: fine
        assertEquals(listOf("Other", "SET"), p.state.value.playlists)
        assertNull(p.state.value.error)
        p.handle(PlayerCommand.RenamePlaylist("Nothing", "X")) // unknown: ignored
        p.handle(PlayerCommand.RenamePlaylist("SET", " / ")) // nothing left of the name: ignored
        assertEquals(listOf("Other", "SET"), p.state.value.playlists)

        val r = p.startUpload(UploadStart(1, "SET", "Song.wav", 10)) {}
        p.handle(PlayerCommand.RenamePlaylist("SET", "Later"))
        p.handle(PlayerCommand.DeletePlaylist("SET"))
        assertEquals(listOf("Other", "SET"), p.state.value.playlists, "not while a song is arriving")
        assertTrue(p.state.value.error.orEmpty().startsWith("Songs are still being added to SET"))
        r.abort()
        p.handle(PlayerCommand.RenamePlaylist("SET", "Later"))
        assertEquals(listOf("Later", "Other"), p.state.value.playlists)
    }

    @Test
    fun deletingAPlaylistRemovesItsSongsStopsThePlayingOneAndShowsAnother() {
        wav("Set/01 Long.wav", ms = 3_000); wav("Set/02 B.wav"); wav("Other/01 X.wav")
        File(dir, "Set/notes.txt").writeText("setlist")
        val p = player(paced = true)
        p.handle(PlayerCommand.SelectPlaylist("Set"))
        p.handle(PlayerCommand.SetEnd("Set/02 B.wav", TrackEnd.LOOP))
        p.handle(PlayerCommand.Play("Set/01 Long.wav"))
        eventually("started") { outputs.isNotEmpty() }

        p.handle(PlayerCommand.DeletePlaylist("Set"))
        assertNull(p.state.value.playing)
        assertTrue(outputs[0].closed, "the song playing stopped")
        assertFalse(File(dir, "Set").exists())
        assertEquals(listOf("Other"), p.state.value.playlists)
        assertEquals("Other", p.state.value.playlist)
        assertEquals("Other/01 X.wav", p.state.value.cued)
        assertNull(p.state.value.error)
        assertFalse(File(dir, WavPlayer.SETTINGS_FILE).readText().contains("Set/"), "its end settings are forgotten")

        p.handle(PlayerCommand.DeletePlaylist("Other"))
        assertEquals(emptyList(), p.state.value.playlists)
        assertNull(p.state.value.playlist)
        assertNull(p.state.value.cued)
    }

    @Test
    fun deletingAPlaylistNeverFollowsALinkOrEmptiesAFolderInsideIt() {
        wav("Set/01 A.wav")
        val outside = File(dir.parentFile, "outside-${System.nanoTime()}").apply { mkdirs(); File(this, "keep.txt").writeText("mine") }
        java.nio.file.Files.createSymbolicLink(File(dir, "Set/link").toPath(), outside.toPath())
        File(dir, "Set/sub").mkdirs()
        File(dir, "Set/sub/inner.txt").writeText("left")
        val p = player()
        p.handle(PlayerCommand.DeletePlaylist("Set"))
        assertTrue(File(outside, "keep.txt").exists(), "what the link pointed to is untouched")
        assertTrue(File(dir, "Set/sub/inner.txt").exists(), "a folder inside is left")
        assertFalse(File(dir, "Set/01 A.wav").exists(), "the songs are gone")
        assertFalse(java.nio.file.Files.exists(File(dir, "Set/link").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS), "the link itself is gone")
        assertTrue(p.state.value.error.orEmpty().contains("holds other things"))
        outside.deleteRecursively()
    }

    @Test
    fun withOneUsbPairOnlyCh7IsOffered() {
        // Windows: Java offers the L6max's audio as a stereo device, so only USB 1/2 (CH7).
        wav("Set/01 A.wav")
        java.io.File(dir, WavPlayer.SETTINGS_FILE).writeText("""{"output": "CH8"}""")
        val p = WavPlayer(dir, scope, { FakeOutput(false) }, { true }, outputPairs = { 1 }, rescanInterval = 100.milliseconds, converter = null, minFreeBytes = 0)
        assertEquals(1, p.state.value.usbPairs)
        assertEquals(PlayerOutput.CH7, p.state.value.output, "an earlier CH8 becomes CH7")
        p.handle(PlayerCommand.SetOutput(PlayerOutput.CH8))
        assertEquals(PlayerOutput.CH7, p.state.value.output, "CH8 can't be chosen")
        p.shutdown()
    }

    @Test
    fun uploadedWavGoesOnTheEndWithoutItsAlbumNumber() {
        wav("Set/01 A.wav"); wav("Set/02 B.wav")
        val p = player(converter = null)
        val done = p.upload("Set", "07 - Yesterday.wav", wavBytes(ms = 300))
        assertEquals(UploadStage.DONE, done.stage, done.message)
        assertEquals("Set/03 Yesterday.wav", done.file)
        assertEquals(listOf("A", "B", "Yesterday"), p.titles("Set"))
        assertTrue(near(300, p.tracks.value.last().durationMs.toInt()))
        assertTrue(File(dir, "Set").listFiles().orEmpty().none { it.name.startsWith(".upload") || it.name.startsWith(".converting") })
    }

    @Test
    fun uploadToANewPlaylistCreatesIt() {
        val p = player(converter = null)
        val done = p.upload("Encores", "Hey Jude.wav", wavBytes())
        assertEquals("Encores/01 Hey Jude.wav", done.file)
        assertEquals(listOf("Encores"), p.state.value.playlists)
    }

    @Test
    fun theHundredthSongMakesTheNumbersWider() {
        for (i in 1..9) wav("Set/0$i S$i.wav", ms = 20)
        for (i in 10..99) wav("Set/$i S$i.wav", ms = 20)
        val p = player(converter = null)
        assertEquals(99, p.titles("Set").size)
        assertEquals("Set/100 Last.wav", p.upload("Set", "Last.wav", wavBytes(ms = 20)).file)
        assertEquals("001 S1.wav", names("Set").first(), "three digits for all, so they still sort")
        assertEquals("S1", p.titles("Set").first())
    }

    @Test
    fun mp3sAreConvertedTo48kWavWhenUploadedOrCopiedIn() {
        val ffmpeg = FfmpegConverter.find() ?: return // conversion needs ffmpeg (installed on the Pi)
        val bytes = makeMp3()
        val p = player(converter = ffmpeg)
        val done = p.upload("Set", "03 Yesterday.mp3", bytes)
        assertEquals(UploadStage.DONE, done.stage, done.message)
        assertEquals("Set/01 Yesterday.wav", done.file)
        val format = AudioSystem.getAudioFileFormat(File(dir, "Set/01 Yesterday.wav")).format
        assertEquals(48_000f, format.sampleRate)
        assertEquals(16, format.sampleSizeInBits)
        assertTrue(near(1_000, p.tracks.value.single().durationMs.toInt()), "was ${p.tracks.value.single().durationMs}")

        File(dir, "Set/Help.mp3").writeBytes(bytes) // copied in by hand
        eventually("converted and added") { names("Set") == listOf("01 Yesterday.wav", "02 Help.wav") }
    }

    @Test
    fun withoutFfmpegOnlyWavsCanBeAdded() {
        val p = player(converter = null)
        val failed = p.upload("Set", "Song.mp3", ByteArray(5_000) { it.toByte() })
        assertEquals(UploadStage.FAILED, failed.stage)
        assertTrue("ffmpeg" in failed.message.orEmpty(), failed.message)
        assertTrue(p.titles("Set").isEmpty())
    }

    @Test
    fun badFilesAreRefusedOrReportedNotAdded() {
        val p = player()
        val e = runCatching { p.startUpload(UploadStart(1, "Set", "notes.txt")) {} }.exceptionOrNull()
        assertTrue("isn't a music file" in e?.message.orEmpty())
        assertNotNull(runCatching { p.startUpload(UploadStart(1, " / ", "a.wav")) {} }.exceptionOrNull(), "no playlist")
        val failed = p.upload("Set", "Broken.flac", ByteArray(5_000) { 7 })
        assertEquals(UploadStage.FAILED, failed.stage)
        assertTrue(p.titles("Set").isEmpty())
    }

    @Test
    fun anAbortedUploadLeavesNothingBehind() {
        val p = player()
        val r = p.startUpload(UploadStart(1, "Set", "a.wav")) {}
        r.write(ByteArray(1000), 0, 1000)
        r.abort()
        assertEquals(emptyList(), File(dir, "Set").list().orEmpty().toList())
    }

    @Test
    fun uploadTitlesAndSafeNames() {
        assertEquals("Yesterday", WavPlayer.uploadTitle("03 - Yesterday.mp3"))
        assertEquals("Yesterday", WavPlayer.uploadTitle("03. Yesterday.flac"))
        assertEquals("Yesterday", WavPlayer.uploadTitle("03 Yesterday.wav"))
        assertEquals("1999", WavPlayer.uploadTitle("1999.mp3"))
        // The price of dropping album track numbers: a title that starts with a number loses it.
        assertEquals("Red Balloons", WavPlayer.uploadTitle("99 Red Balloons.mp3"))
        assertEquals("AC DC", WavPlayer.cleanName("AC/DC", 60))
        assertEquals("Song", WavPlayer.uploadTitle("....mp3"))
        assertEquals("Help", WavPlayer.titleOf("12 Help.wav"))
        assertEquals("1999", WavPlayer.titleOf("1999.wav"))
    }

    // ---- Playback -----------------------------------------------------------------------------

    @Test
    fun playsOnUsb12OrUsb34At48k() {
        wav("Set/01 a.wav", ms = 200)
        val p = player()
        p.handle(PlayerCommand.Play("Set/01 a.wav"))
        eventually("finished") { p.state.value.playing == null && outputs.size == 1 && outputs[0].closed }
        val f = outputs[0].frames()
        val frames = f.size / 4
        assertTrue(near(9600, frames), "0.2 s at 48 kHz, was $frames frames")
        val mid = (frames / 2) * 4
        assertEquals(listOf(8000, -8000, 0, 0), f.slice(mid until mid + 4).map { it.toInt() }, "CH7 = USB 1/2")

        p.handle(PlayerCommand.SetOutput(PlayerOutput.CH8))
        p.handle(PlayerCommand.Play("Set/01 a.wav"))
        eventually("finished again") { outputs.size == 2 && outputs[1].closed }
        val g = outputs[1].frames()
        val m = (g.size / 8) * 4
        assertEquals(listOf(0, 0, 8000, -8000), g.slice(m until m + 4).map { it.toInt() }, "CH8 = USB 3/4")
    }

    @Test
    fun monoFilesPlayOnBothSides() {
        wav("Set/01 mono.wav", left = 5000, right = null, rate = 48_000f)
        val p = player()
        p.handle(PlayerCommand.Play("Set/01 mono.wav"))
        eventually("finished") { outputs.size == 1 && outputs[0].closed }
        val f = outputs[0].frames()
        val mid = (f.size / 8) * 4
        assertEquals(listOf(5000, 5000, 0, 0), f.slice(mid until mid + 4).map { it.toInt() })
    }

    @Test
    fun stopAtTheEndCuesTheNextSongInThePlaylist() {
        wav("Set/01 1.wav"); wav("Set/02 2.wav"); wav("Zed/01 other.wav")
        val p = player()
        p.handle(PlayerCommand.SelectPlaylist("Set"))
        p.handle(PlayerCommand.Play("Set/01 1.wav"))
        eventually("stopped by itself") { p.state.value.playing == null && outputs.isNotEmpty() && outputs[0].closed }
        assertEquals("Set/02 2.wav", p.state.value.cued)
        p.handle(PlayerCommand.Play("Set/02 2.wav"))
        eventually("stopped again") { outputs.size == 2 && outputs[1].closed && p.state.value.playing == null }
        assertTrue(p.state.value.cued != "Zed/01 other.wav", "never runs on into another playlist")
    }

    @Test
    fun playNextCarriesOnThroughThePlaylistWithoutReopeningTheOutput() {
        wav("Set/01 1.wav"); wav("Set/02 2.wav"); wav("Set/03 3.wav"); wav("Zed/01 other.wav")
        val p = player()
        p.handle(PlayerCommand.SetEnd("Set/01 1.wav", TrackEnd.NEXT))
        p.handle(PlayerCommand.SetEnd("Set/02 2.wav", TrackEnd.NEXT))
        p.handle(PlayerCommand.SetEnd("Set/03 3.wav", TrackEnd.NEXT))
        p.handle(PlayerCommand.Play("Set/01 1.wav"))
        eventually("all three played") { p.state.value.playing == null && outputs.isNotEmpty() && outputs[0].closed }
        assertEquals(1, outputs.size, "gapless: one output for the run")
        assertTrue(near(3 * 9600, outputs[0].frames().size / 4), "stopped at the end of the playlist")
    }

    @Test
    fun loopRepeatsUntilStoppedAndStopKeepsTheSongCued() {
        wav("Set/01 1.wav", ms = 100); wav("Set/02 2.wav")
        val p = player()
        p.handle(PlayerCommand.SetEnd("Set/01 1.wav", TrackEnd.LOOP))
        p.handle(PlayerCommand.Play("Set/01 1.wav"))
        eventually("looped a few times") { outputs.isNotEmpty() && outputs[0].frames().size / 4 > 3 * 4800 }
        assertEquals("Set/01 1.wav", p.state.value.playing)
        p.handle(PlayerCommand.Stop)
        assertNull(p.state.value.playing)
        assertEquals("Set/01 1.wav", p.state.value.cued, "a stopped song stays cued")
        eventually("output closed") { outputs[0].closed }
    }

    @Test
    fun pauseHoldsThePositionAndResumeCarriesOn() {
        wav("Set/01 long.wav", ms = 3_000)
        val p = player(paced = true)
        p.handle(PlayerCommand.Play("Set/01 long.wav"))
        eventually("playing a while") { outputs.isNotEmpty() && outputs[0].framesPlayed > 4800 }
        p.handle(PlayerCommand.Pause)
        assertTrue(p.state.value.paused)
        Thread.sleep(100)
        val held = outputs[0].framesPlayed
        Thread.sleep(200)
        assertEquals(held, outputs[0].framesPlayed, "nothing written while paused")
        p.handle(PlayerCommand.Resume)
        eventually("moving again") { outputs[0].framesPlayed > held }
        p.handle(PlayerCommand.Stop)
    }

    @Test
    fun playingAnotherSongStopsTheFirstBeforeOpeningTheOutput() {
        wav("Set/01 long.wav", ms = 3_000); wav("Set/02 b.wav")
        val p = player(paced = true)
        p.handle(PlayerCommand.Play("Set/01 long.wav"))
        eventually("started") { outputs.isNotEmpty() }
        p.handle(PlayerCommand.Play("Set/02 b.wav"))
        assertTrue(outputs[0].closed, "the first output is closed before the second opens")
        assertEquals("Set/02 b.wav", p.state.value.playing)
        p.handle(PlayerCommand.Stop)
    }

    @Test
    fun endSettingsAndOutputAreKeptInTheMusicFolder() {
        wav("Set/01 1.wav")
        player().apply {
            handle(PlayerCommand.SetEnd("Set/01 1.wav", TrackEnd.LOOP))
            handle(PlayerCommand.SetOutput(PlayerOutput.CH8))
        }
        val again = player()
        assertEquals(TrackEnd.LOOP, again.tracks.value.single().end)
        assertEquals(PlayerOutput.CH8, again.state.value.output)
    }

    @Test
    fun volumeScalesTheSamplesAndIsKept() {
        wav("Set/01 a.wav", ms = 200, rate = 48_000f)
        val p = player()
        p.handle(PlayerCommand.SetVolume(50))
        assertEquals(50, p.state.value.volume)
        p.handle(PlayerCommand.Play("Set/01 a.wav"))
        eventually("finished") { outputs.size == 1 && outputs[0].closed }
        val f = outputs[0].frames()
        val mid = (f.size / 8) * 4
        assertEquals(listOf(2000, -2000, 0, 0), f.slice(mid until mid + 4).map { it.toInt() }, "50 % = a quarter of the level")
        eventually("saved") { player().state.value.volume == 50 }

        p.handle(PlayerCommand.SetVolume(0))
        p.handle(PlayerCommand.Play("Set/01 a.wav"))
        eventually("finished again") { outputs.size == 2 && outputs[1].closed }
        val g = outputs[1].frames()
        assertTrue(g.drop(g.size / 2).all { it.toInt() == 0 }, "0 % is silent")
        p.handle(PlayerCommand.SetVolume(250))
        assertEquals(100, p.state.value.volume, "never boosted past the file's level")
    }

    @Test
    fun withoutTheL6maxItSaysSoInsteadOfPlaying() {
        wav("Set/01 1.wav")
        available = false
        val p = player()
        assertEquals(false, p.state.value.ready)
        p.handle(PlayerCommand.Play("Set/01 1.wav"))
        assertNull(p.state.value.playing)
        assertNotNull(p.state.value.error)
        assertTrue(outputs.isEmpty())
    }

    /** A one-second MP3, made with ffmpeg (outside the music folder). */
    private fun makeMp3(): ByteArray {
        val work = Files.createTempDirectory("l6desk-mp3").toFile()
        try {
            val wav = File(work, "in.wav").apply { writeBytes(wavBytes(ms = 1_000, rate = 44_100f)) }
            val mp3 = File(work, "out.mp3")
            val p = ProcessBuilder("ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", wav.path, mp3.path).redirectErrorStream(true).start()
            p.inputStream.readAllBytes()
            check(p.waitFor() == 0) { "ffmpeg couldn't make an MP3" }
            return mp3.readBytes()
        } finally {
            work.deleteRecursively()
        }
    }
}

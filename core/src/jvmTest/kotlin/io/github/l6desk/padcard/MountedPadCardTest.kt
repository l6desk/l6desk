package io.github.l6desk.padcard

import io.github.l6desk.player.Track
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PAD SOUNDS at the Pi: a fake card "mounted" in a temp folder, and a music library beside it. */
class MountedPadCardTest {
    private val base: File = Files.createTempDirectory("mounted-pad").toFile()
    private val mount = File(base, "card")
    private val music = File(base, "music")
    private val request = File(base, "run/eject-card").apply { parentFile.mkdirs() }
    private val song = Track("Openers/01 Fanfare.wav", "Fanfare", 500, playlist = "Openers")
    private val tracks = mutableListOf(song)
    private val host = MountedPadCard(mount, music, { tracks }, File(music, MountedPadCard.BACKUPS), request)

    @AfterTest
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun cardIn() {
        File(mount, "ZOOM_L6max.sys").apply { parentFile.mkdirs() }.writeText("ZOOM")
        for (p in 1..4) File(mount, "SOUND_PAD/PAD$p").mkdirs()
    }

    private fun wav(file: File) {
        file.parentFile.mkdirs()
        val frames = 24_000
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(ByteArray(frames * 4)), AudioFormat(48_000f, 16, 2, true, false), frames.toLong()), AudioFileFormat.Type.WAVE, file)
    }

    @Test
    fun seesTheCardOnlyWhileMounted() {
        assertFalse(host.refresh())
        assertNull(host.card.value)
        cardIn()
        assertTrue(host.refresh())
        assertEquals("at the Pi", host.card.value?.location)
        assertEquals(4, host.card.value?.pads?.size)
    }

    @Test
    fun putsALibrarySongOnAPadBackingUpTheOldSound() {
        cardIn()
        wav(File(music, song.file))
        wav(File(mount, "SOUND_PAD/PAD2/261009_081443.wav"))
        val result = host.handle(PadCommand.Set(7, 2, song.file))
        assertEquals(PadResult(7, "Fanfare.wav is on the card for pad 2. When you've finished, EJECT CARD before switching off file transfer mode."), result)
        assertEquals(listOf("Fanfare.wav"), File(mount, "SOUND_PAD/PAD2").list()?.toList())
        assertEquals("Fanfare.wav", host.card.value?.pads?.get(1)?.file, "the state is refreshed after the change")
        val backups = File(music, MountedPadCard.BACKUPS).walk().filter { it.isFile }.map { it.name }.toList()
        assertEquals(listOf("261009_081443.wav"), backups)
        assertTrue(File(music, song.file).isFile, "the song stays in the library")
    }

    @Test
    fun refusesSongsOutsideTheLibrary() {
        cardIn()
        File(base, "secret.wav").writeText("not yours")
        tracks += Track("../secret.wav", "Secret", 1)
        val result = host.handle(PadCommand.Set(1, 1, "../secret.wav"))
        assertTrue(result.error)
        val unknown = host.handle(PadCommand.Set(2, 1, "Openers/99 Not there.wav"))
        assertEquals(PadResult(2, "That song isn't on the Pi any more", error = true), unknown)
        assertEquals(emptyList(), File(mount, "SOUND_PAD/PAD1").list()?.toList())
    }

    @Test
    fun clearsAPadAndSaysSoWhenTheCardHasGone() {
        cardIn()
        wav(File(mount, "SOUND_PAD/PAD4/x.wav"))
        assertEquals(PadResult(3, "Pad 4 has no sound now."), host.handle(PadCommand.Clear(3, 4)))
        mount.deleteRecursively()
        assertEquals(PadResult(4, "The L6max's card isn't connected to the Pi any more", error = true), host.handle(PadCommand.Clear(4, 4)))
    }

    @Test
    fun ejectsThroughTheRootHelper() {
        cardIn()
        // Stands in for l6desk-card-eject.path: unmounts once the request appears, then removes it.
        val helper = thread {
            while (!request.exists()) Thread.sleep(20)
            mount.deleteRecursively()
            request.delete()
        }
        val result = host.handle(PadCommand.Eject(5))
        helper.join(5_000)
        assertEquals(PadResult(5, "Card ejected. Now switch off file transfer mode on the L6max, and its pads play the new sounds."), result)
        assertNull(host.card.value)
        assertFalse(request.exists())
    }
}

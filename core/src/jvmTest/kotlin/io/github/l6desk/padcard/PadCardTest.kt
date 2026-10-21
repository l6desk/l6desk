package io.github.l6desk.padcard

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PadCard on a fake card laid out like a real L6max's (docs/hardware-notes.md). */
class PadCardTest {
    private val root: File = Files.createTempDirectory("zoom-sd").toFile()
    private val backups: File = Files.createTempDirectory("pad-backups").toFile()

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
        backups.deleteRecursively()
    }

    /** A card like the one seen: PAD1 assigned its second recording, PAD2–4 "NO ASSIGN". */
    private fun card(vararg assigned: String?): PadCard {
        File(root, "ZOOM_L6max.sys").writeBytes("ZOOM SYSTEM FILE".toByteArray())
        for (p in 1..4) File(root, "SOUND_PAD/PAD$p").mkdirs()
        File(root, "SOUND_PAD/L6MAXPADSETTING.zst").writeBytes(settings(*assigned))
        return PadCard(root)
    }

    private fun settings(vararg assigned: String?): ByteArray {
        val b = ByteArray(4250)
        fun put(at: Int, text: String) = text.forEachIndexed { i, c -> b[at + i * 2] = c.code.toByte(); b[at + i * 2 + 1] = (c.code shr 8).toByte() }
        put(0, "ZOOM L6MAX  PAD SETTING VER0001")
        for (i in 0 until 4) put(0x60 + i * 0x20a, assigned.getOrNull(i)?.let { "A:\\SOUND_PAD\\PAD${i + 1}\\$it" } ?: "NO ASSIGN")
        return b
    }

    private fun wav(file: File, ms: Int = 500, float: Boolean = false, rate: Float = 48_000f) {
        file.parentFile.mkdirs()
        val frames = (rate * ms / 1000).toInt()
        val bytesPer = if (float) 4 else 2
        val buf = ByteBuffer.allocate(frames * 2 * bytesPer).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * 2) { if (float) buf.putFloat(0.25f) else buf.putShort(8000) }
        val format = if (float) AudioFormat(AudioFormat.Encoding.PCM_FLOAT, rate, 32, 2, 8, rate, false) else AudioFormat(rate, 16, 2, true, false)
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(buf.array()), format, frames.toLong()), AudioFileFormat.Type.WAVE, file)
    }

    @Test
    fun recognisesTheCardAndFindsItAmongMountedDrives() {
        card()
        assertTrue(PadCard.isCard(root))
        assertTrue(!PadCard.isCard(backups))
    }

    @Test
    fun readsEachPadsSoundAsTheMixerHasIt() {
        val c = card("261009_081443.wav", null, null, null)
        wav(File(root, "SOUND_PAD/PAD1/261009_081001.wav"), float = true)
        wav(File(root, "SOUND_PAD/PAD1/261009_081443.wav"), ms = 1000, float = true)
        wav(File(root, "SOUND_PAD/PAD2/Rising.wav"))
        wav(File(root, "SOUND_PAD/PAD3/a.wav")); wav(File(root, "SOUND_PAD/PAD3/b.wav"))
        assertEquals(listOf("261009_081443.wav", null, null, null), c.assignedFiles())
        val pads = c.state().pads
        assertEquals(PadSound(1, "261009_081443.wav", 1000, others = 1), pads[0], "the assigned file, not the first")
        assertEquals("Rising.wav", pads[1].file, "the only file in an unassigned folder")
        assertEquals(PadSound(3, null, null, others = 2), pads[2], "several files and none assigned: can't say which")
        assertEquals(PadSound(4, null), pads[3])
    }

    @Test
    fun settingASoundBacksUpTheOldOnesAndLeavesJustTheNewFile() {
        val c = card("261009_081443.wav", null, null, null)
        wav(File(root, "SOUND_PAD/PAD1/261009_081001.wav"), float = true)
        wav(File(root, "SOUND_PAD/PAD1/261009_081443.wav"), float = true)
        val recorded = File(root, "SOUND_PAD/PAD1/261009_081443.wav").readBytes()
        val source = File(backups.parentFile, "jingle-${System.nanoTime()}.wav").also { wav(it, rate = 44_100f) }
        try {
            c.setSound(1, source, "03 - Big: Jingle?", converter = null, backup = backups)
        } finally {
            source.delete()
        }
        assertEquals(listOf("03 - Big Jingle.wav"), c.padDir(1).list()!!.toList(), "just the new file (with a FAT-safe name)")
        val format = AudioSystem.getAudioFileFormat(File(c.padDir(1), "03 - Big Jingle.wav")).format
        assertEquals(48_000f, format.sampleRate, "converted to 48 kHz")
        assertEquals(listOf("261009_081001.wav", "261009_081443.wav"), File(backups, "PAD1").list()!!.sorted())
        assertContentEquals(recorded, File(backups, "PAD1/261009_081443.wav").readBytes(), "the recording is safe")
        assertEquals("03 - Big Jingle.wav", c.sound(1).file, "the assigned file has gone, so the folder's one is it")
        assertTrue(File(root, "SOUND_PAD/L6MAXPADSETTING.zst").readBytes().contentEquals(settings("261009_081443.wav", null, null, null)), "the mixer's settings are never written")
    }

    @Test
    fun clearingBacksUpAndEmptiesThePad() {
        val c = card(null, "Rising.wav", null, null)
        wav(File(root, "SOUND_PAD/PAD2/Rising.wav"))
        c.clear(2, backups)
        assertTrue(c.padDir(2).list()!!.isEmpty())
        assertTrue(File(backups, "PAD2/Rising.wav").isFile)
        assertNull(c.sound(2).file)
        c.clear(4, backups) // nothing there: nothing to do
        assertTrue(!File(backups, "PAD4").exists())
    }

    @Test
    fun anMp3NeedsFfmpegAndAFailureLeavesThePadAsItWas() {
        val c = card(null, null, "Old.wav", null)
        wav(File(root, "SOUND_PAD/PAD3/Old.wav"))
        val mp3 = File(backups, "song.mp3").apply { writeText("not really") }
        assertFailsWith<IllegalStateException> { c.setSound(3, mp3, "song", converter = null, backup = backups) }
        assertEquals(listOf("Old.wav"), c.padDir(3).list()!!.toList(), "untouched, and no half-written file left")
        val broken = File(backups, "broken.wav").apply { writeText("not a wav") }
        assertFailsWith<Exception> { c.setSound(3, broken, "broken", converter = null, backup = backups) }
        assertEquals(listOf("Old.wav"), c.padDir(3).list()!!.toList())
    }

    @Test
    fun namesTheFatFileSystemTakes() {
        assertEquals("AC DC Back In Black", PadCard.fatSafe("AC/DC: Back \"In\" Black"))
        assertEquals("Pad sound", PadCard.fatSafe(" ... "))
        assertEquals(60, PadCard.fatSafe("x".repeat(100)).length)
    }

    @Test
    fun aMissingOrShortSettingsFileMeansNothingAssigned() {
        val c = card()
        File(root, "SOUND_PAD/L6MAXPADSETTING.zst").writeBytes(ByteArray(10))
        assertEquals(List(4) { null }, c.assignedFiles())
        File(root, "SOUND_PAD/L6MAXPADSETTING.zst").delete()
        assertEquals(List(4) { null }, c.assignedFiles())
    }
}

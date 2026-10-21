package io.github.l6desk.recordings

import io.github.l6desk.padcard.PadCard
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RECORDINGS against the real L6max's card, mounted on this computer (file transfer mode). Only
 * with L6MAX_CARD=1; writes to L6MAX_CARD_OUT (default build/real-recordings). Reads the card only.
 */
class RealCardRecordingsTest {
    @Test
    fun downloadsFromTheRealCard() = runBlocking {
        if (System.getenv("L6MAX_CARD") != "1") return@runBlocking
        val root = requireNotNull(PadCard.find()) { "No L6max card mounted" }.root
        val card = RecorderCard(root)
        var t = System.currentTimeMillis()
        val r = card.measure(card.list().first())
        println("Newest: ${r.folder} (${r.title}), ${r.durationMs} ms, measured in ${System.currentTimeMillis() - t} ms")
        r.files.forEach { println("  ${it.name}  ${it.label}  peak ${it.peakDb?.let { p -> "%.1f".format(p) }} dBFS  used=${it.used}") }
        val out = File(System.getenv("L6MAX_CARD_OUT") ?: "build/real-recordings")
        out.deleteRecursively()
        val copier = RecordingCopier(card)
        for (options in listOf(RecordingOptions(), RecordingOptions(RecordingSelection.USED, RecordingFormat.PCM24, normalise = true), RecordingOptions(RecordingSelection.USED, RecordingFormat.PCM24))) {
            t = System.currentTimeMillis()
            val result = copier.copy(r, options, out) {}
            val files = result.files
            val ms = System.currentTimeMillis() - t
            val mb = files.sumOf { it.length() } / 1e6
            println("$options: ${files.map { it.name }} ${"%.0f".format(mb)} MB in $ms ms (${"%.1f".format(mb * 1000 / ms)} MB/s), clipped ${result.clipped}")
        }
        assertEquals("MASTER_02.wav", r.master?.name)
        assertEquals(listOf(1, 5), r.tracks.filter { it.used }.map { it.channel })
        assertTrue(File(root, "RECORDER/${r.folder}/MASTER_02.wav").readBytes().contentEquals(File(out, "MASTER_02.wav").readBytes()))
    }
}

package io.github.l6desk.padcard

import io.github.l6desk.player.AudioConverter
import io.github.l6desk.player.WavPlayer
import io.github.l6desk.player.WavWriter
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.sound.sampled.AudioSystem

/**
 * The L6max's memory card at [root], in file transfer mode. Each pad's sound is a WAV in
 * `SOUND_PAD/PAD1`–`PAD4`. The mixer keeps its own record of each pad's file
 * (`SOUND_PAD/L6MAXPADSETTING.zst`, read here but never written); when that file has gone, it
 * adopts the one in the folder (verified on a real L6max, docs/hardware-notes.md). So a pad's
 * sound is set by leaving just the new file in its folder: anything there before is copied to a
 * backup folder first, so a sound recorded on the mixer is never lost.
 */
class PadCard(val root: File) {
    private val soundPad = File(root, "SOUND_PAD")

    fun padDir(pad: Int) = File(soundPad, "PAD$pad")

    fun state(): PadCardState = PadCardState(root.path, (1..PADS).map(::sound))

    fun sound(pad: Int): PadSound {
        val wavs = wavs(pad)
        val assigned = assignedFiles().getOrNull(pad - 1)?.takeIf { it in wavs.map(File::getName) }
        val file = assigned ?: wavs.singleOrNull()?.name
        return PadSound(pad, file, file?.let { durationMs(File(padDir(pad), it)) }, others = wavs.size - (if (file != null) 1 else 0))
    }

    /**
     * Makes [source] pad [pad]'s sound, called [title]: converted to a 48 kHz WAV (with [converter]
     * for MP3/AAC/FLAC, or Java Sound for WAV), the pad's old files backed up into [backup], then
     * removed. Throws with a message for the user if it can't.
     */
    fun setSound(pad: Int, source: File, title: String, converter: AudioConverter?, backup: File) {
        require(pad in 1..PADS)
        val dir = padDir(pad).apply { mkdirs() }
        val name = fatSafe(title) + ".wav"
        val staged = File(dir, "$name.l6desk-new")
        try {
            convert(source, staged, converter)
            backUp(pad, backup)
            wavs(pad).forEach { check(it.delete() || !it.exists()) { "Couldn't remove ${it.name} from the card" } }
            val target = File(dir, name)
            check(staged.renameTo(target)) { "Couldn't store $name on the card" }
            flush()
        } finally {
            staged.delete()
        }
    }

    /**
     * Writes everything still waiting for the card (the file's data and the folder entries), so a
     * card switched off or unplugged straight afterwards still has the new sound. Without this,
     * Linux finishes in the background, and a card that goes early is left with a file of 0 bytes
     * (seen on the real mixer). Windows writes removable drives straight through.
     */
    fun flush() {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        val synced = runCatching {
            val p = ProcessBuilder("sync", "-f", root.path).redirectErrorStream(true).start()
            p.inputStream.readAllBytes()
            p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
        check(synced) { "Couldn't finish writing to the card: is it still connected?" }
    }

    /** Removes pad [pad]'s sound, after backing it up into [backup]. */
    fun clear(pad: Int, backup: File) {
        backUp(pad, backup)
        wavs(pad).forEach { check(it.delete() || !it.exists()) { "Couldn't remove ${it.name} from the card" } }
        flush()
    }

    /** Copies the pad's WAVs into [backup]/PADn, checking each copy's size. */
    private fun backUp(pad: Int, backup: File) {
        val files = wavs(pad)
        if (files.isEmpty()) return
        val dir = File(backup, "PAD$pad").apply { mkdirs() }
        for (f in files) {
            val copy = File(dir, f.name)
            f.copyTo(copy, overwrite = true)
            check(copy.length() == f.length()) { "Couldn't back up ${f.name}" }
        }
    }

    /** Converts [source] into [target], a WAV the mixer plays (48 kHz, 16-bit stereo), synced to the card. */
    private fun convert(source: File, target: File, converter: AudioConverter?) {
        val isWav = source.name.endsWith(".wav", ignoreCase = true)
        when {
            converter != null -> converter.convert(source, target)
            isWav -> WavPlayer.openStereo48k(source).use { WavWriter.write(it, target) }
            else -> error("Only WAV files can be used: install ffmpeg for MP3, AAC and FLAC")
        }
        FileOutputStream(target, true).use { it.fd.sync() }
    }

    private fun wavs(pad: Int): List<File> =
        padDir(pad).listFiles { f -> f.isFile && f.name.endsWith(".wav", ignoreCase = true) && !f.name.startsWith(".") }.orEmpty().sortedBy { it.name.lowercase() }

    /** The file name the mixer has recorded for each pad (null: "NO ASSIGN", or unreadable). */
    fun assignedFiles(): List<String?> {
        val bytes = runCatching { File(soundPad, SETTINGS).readBytes() }.getOrNull() ?: return List(PADS) { null }
        return (0 until PADS).map { i -> parseAssigned(bytes, i) }
    }

    companion object {
        const val PADS = 4
        const val SETTINGS = "L6MAXPADSETTING.zst"
        const val SYSTEM_FILE = "ZOOM_L6max.sys"

        // L6MAXPADSETTING.zst: a UTF-16LE header, then one 522-byte block per pad starting at 0x60,
        // each beginning with the file's path ("A:\SOUND_PAD\PAD1\x.wav") or "NO ASSIGN".
        private const val FIRST_PAD = 0x60
        private const val PAD_BLOCK = 0x20a
        private const val PATH_BYTES = 0x200

        internal fun parseAssigned(bytes: ByteArray, index: Int): String? {
            val start = FIRST_PAD + index * PAD_BLOCK
            if (bytes.size < start + PATH_BYTES) return null
            val chars = StringBuilder()
            var i = start
            while (i + 1 < start + PATH_BYTES) {
                val c = ((bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)).toChar()
                if (c == '\u0000') break
                chars.append(c)
                i += 2
            }
            val path = chars.toString().trim()
            return path.takeIf { it.isNotEmpty() && it != "NO ASSIGN" }?.substringAfterLast('\\')
        }

        /** Whether [dir] is the root of an L6max card. */
        fun isCard(dir: File): Boolean = File(dir, "SOUND_PAD").isDirectory && File(dir, SYSTEM_FILE).isFile

        /**
         * The card, if one is mounted: under /media/<user> and /run/media/<user> (Linux),
         * /Volumes (macOS), or a drive letter (Windows).
         */
        fun find(user: String = System.getProperty("user.name").orEmpty()): PadCard? {
            val candidates = listOf(File("/media/$user"), File("/run/media/$user"), File("/Volumes"))
                .flatMap { it.listFiles()?.toList().orEmpty() } + File.listRoots().orEmpty()
            return candidates.firstOrNull { runCatching { isCard(it) }.getOrDefault(false) }?.let(::PadCard)
        }

        /** A name the card's FAT file system takes: no \ / : * ? " < > |, at most 60 characters. */
        fun fatSafe(title: String): String =
            title.map { if (it in "\\/:*?\"<>|" || it.isISOControl()) ' ' else it }.joinToString("")
                .replace(Regex("\\s+"), " ").trim().trim('.').take(60).trim().ifEmpty { "Pad sound" }

        /** A folder for this session's backups: <base>/2026-10-15 10-30-05. */
        fun backupFolder(base: File): File = File(base, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")))

        private fun durationMs(f: File): Long? = runCatching {
            val format = BufferedInputStream(Files.newInputStream(f.toPath())).use { AudioSystem.getAudioFileFormat(it) }
            val frames = format.frameLength.toLong()
            if (frames <= 0) null else frames * 1000 / format.format.frameRate.toLong()
        }.getOrNull()
    }
}

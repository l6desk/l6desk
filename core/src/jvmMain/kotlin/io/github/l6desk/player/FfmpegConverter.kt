package io.github.l6desk.player

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Converts with ffmpeg (installed by pi-setup.sh), at low priority so playback isn't disturbed. */
class FfmpegConverter(private val ffmpeg: String) : AudioConverter {
    override fun convert(input: File, output: File) {
        val log = File(output.parentFile, "${output.name}.log")
        val nice = listOf("/usr/bin/nice", "-n", "10").takeIf { File(it[0]).canExecute() }.orEmpty()
        val command = nice + listOf(
            ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", input.path,
            "-map", "0:a:0", "-vn", "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact",
            "-ar", "48000", "-ac", "2", "-c:a", "pcm_s16le", "-f", "wav", output.path,
        )
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                throw IOException("Converting took too long")
            }
            if (process.exitValue() != 0) {
                val why = runCatching { log.readLines().lastOrNull { it.isNotBlank() } }.getOrNull()
                throw IOException("Can't convert it" + (why?.let { ": ${it.take(160)}" } ?: ""))
            }
        } finally {
            log.delete()
        }
    }

    companion object {
        /**
         * ffmpeg if it's installed, else null (then only WAV files can be added): the usual Linux
         * places, then every folder on the PATH (how Windows finds it, e.g. after `winget install ffmpeg`).
         */
        fun find(): FfmpegConverter? = locate(System.getenv("PATH").orEmpty(), System.getProperty("os.name").orEmpty().startsWith("Windows"))?.let(::FfmpegConverter)

        internal fun locate(path: String, windows: Boolean, exists: (File) -> Boolean = { it.isFile && it.canExecute() }): String? {
            val name = if (windows) "ffmpeg.exe" else "ffmpeg"
            val fixed = if (windows) emptyList() else listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/opt/homebrew/bin/ffmpeg")
            val onPath = path.split(if (windows) ';' else ':').filter { it.isNotBlank() }.map { File(it.trim('"'), name).path }
            return (fixed + onPath).firstOrNull { exists(File(it)) }
        }
    }
}

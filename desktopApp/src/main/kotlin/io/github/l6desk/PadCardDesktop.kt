package io.github.l6desk

import io.github.l6desk.padcard.PadCard
import io.github.l6desk.padcard.PadCardState
import io.github.l6desk.padcard.PadCardSupport
import io.github.l6desk.player.FfmpegConverter
import io.github.l6desk.player.WavPlayer
import io.github.l6desk.player.isAudioFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * PAD SOUNDS on the desktop: finds the L6max's card among mounted drives (Ubuntu mounts it once the
 * package's udev rule is installed), picks sounds with the system's file dialog, backs up what's
 * replaced into ~/Music/L6Desk/Pad backups, and ejects the card on Linux (udisksctl).
 */
class PadCardDesktop(private val scope: CoroutineScope, private val parent: () -> Frame?) : PadCardSupport {
    private val _card = MutableStateFlow<PadCardState?>(null)
    override val card: StateFlow<PadCardState?> = _card.asStateFlow()

    private val backupBase = File(File(System.getProperty("user.home"), "Music"), "L6Desk/Pad backups")
    override val backupLocation: String = backupBase.path

    private var lastDir: String? = System.getProperty("user.home")?.let { home -> File(home, "Music").takeIf { it.isDirectory }?.path ?: home }

    override fun watch(): () -> Unit {
        val job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                refresh()
                delay(2_000)
            }
        }
        return { job.cancel() }
    }

    private fun current(): PadCard? = PadCard.find()

    private fun refresh() {
        _card.value = runCatching { current()?.state() }.getOrNull()
    }

    override suspend fun choose(pad: Int): String? {
        val file = withContext(Dispatchers.Main) {
            val dialog = FileDialog(parent(), "A sound for pad $pad", FileDialog.LOAD).apply {
                directory = lastDir
                setFilenameFilter { _, name -> isAudioFile(name) }
                if (System.getProperty("os.name").orEmpty().startsWith("Windows")) file = "*.wav;*.mp3;*.m4a;*.aac;*.flac"
                isVisible = true // blocks until closed
            }
            dialog.directory?.let { lastDir = it }
            dialog.files.orEmpty().firstOrNull()
        } ?: return null
        return withContext(Dispatchers.IO) {
            val card = current() ?: error("The L6max's card isn't connected any more")
            val title = WavPlayer.uploadTitle(file.name)
            card.setSound(pad, file, title, FfmpegConverter.find(), PadCard.backupFolder(backupBase))
            refresh()
            "${PadCard.fatSafe(title)}.wav is on the card for pad $pad. When you've finished, EJECT CARD before switching off file transfer mode."
        }
    }

    override suspend fun clear(pad: Int): String = withContext(Dispatchers.IO) {
        val card = current() ?: error("The L6max's card isn't connected any more")
        card.clear(pad, PadCard.backupFolder(backupBase))
        refresh()
        "Pad $pad has no sound now."
    }

    override suspend fun eject(): String? = withContext(Dispatchers.IO) {
        if (System.getProperty("os.name").orEmpty() != "Linux") return@withContext null
        val card = current() ?: return@withContext "The card isn't connected."
        val device = run("findmnt", "-n", "-o", "SOURCE", card.root.path).trim().takeIf { it.startsWith("/dev/") }
            ?: return@withContext null
        val unmounted = run("udisksctl", "unmount", "-b", device)
        check(unmounted.contains("Unmounted", ignoreCase = true)) { "Couldn't eject the card: ${unmounted.lines().firstOrNull { it.isNotBlank() }.orEmpty()}" }
        run("udisksctl", "power-off", "-b", device) // tidy, but not needed: unmounting wrote everything
        refresh()
        "Card ejected. Now switch off file transfer mode on the L6max, and its pads play the new sounds."
    }

    private fun run(vararg command: String): String = runCatching {
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(20, TimeUnit.SECONDS)
        out
    }.getOrDefault("")
}

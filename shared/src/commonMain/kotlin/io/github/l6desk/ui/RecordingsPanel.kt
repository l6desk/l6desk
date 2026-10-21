package io.github.l6desk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.l6desk.player.formatTime
import io.github.l6desk.recordings.DownloadProgress
import io.github.l6desk.recordings.Recording
import io.github.l6desk.recordings.RecordingFormat
import io.github.l6desk.recordings.RecordingOptions
import io.github.l6desk.recordings.RecordingSelection
import io.github.l6desk.recordings.RecordingsSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * RECORDINGS (SETUP): downloads the L6max's recordings from its memory card, in file transfer
 * mode like PAD SOUNDS. The recordings are listed newest first, with the newest picked; [options]
 * say what's copied (the newest master take, the tracks with sound, or everything), in what format
 * (as recorded, or 24-bit) and whether normalised. Nothing on the card is changed.
 */
@Composable
fun RecordingsPanel(
    support: RecordingsSupport,
    options: RecordingOptions,
    onOptions: (RecordingOptions) -> Unit,
    onDismiss: () -> Unit,
    /** Why the connected bridge can't offer them (too old, not set up), shown first. */
    note: String? = null,
) {
    val recordings by support.recordings.collectAsState()
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<DownloadProgress?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var downloaded by remember { mutableStateOf<Recording?>(null) }
    var ejecting by remember { mutableStateOf(false) }
    val busy = job != null || ejecting
    DisposableEffect(support) {
        val stop = support.watch()
        onDispose { stop() }
    }

    ModalPanel(
        "RECORDINGS", { if (!busy) onDismiss() }, maxWidth = 760.dp, modifier = Modifier.testTag("recordings-panel"),
        titleTrailing = {
            LampButton("DONE", false, { if (!busy) onDismiss() }, DeskColors.Ink, Modifier.width(96.dp).height(40.dp).testTag("recordings-done"), fontSize = 12.sp)
        },
    ) {
        note?.let { Hint(it, DeskColors.Amber, Modifier.testTag("recordings-note")) }
        val list = recordings
        if (list == null) {
            Hint("Recordings made on the L6max are on its memory card. To download them:", DeskColors.Ink)
            Hint("1.  On the L6max, switch on file transfer mode. While it's on, the mixer doesn't mix or record.")
            Hint("2.  Connect the L6max to ${support.connectTo} by USB. Its card appears here by itself.")
            Hint("Looking for the card…", DeskColors.Amber, Modifier.testTag("recordings-waiting"))
        } else if (list.isEmpty()) {
            Hint("The L6max's card has no recordings.", DeskColors.Amber, Modifier.testTag("recordings-empty"))
        } else {
            val selected = list.firstOrNull { it.folder == picked } ?: list.first()
            val shape = RoundedCornerShape(5.dp)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 230.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(list, key = { it.folder }) { r ->
                    val on = r.folder == selected.folder
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).background(DeskColors.ButtonBottom, shape)
                            .border(if (on) 2.dp else 1.dp, if (on) DeskColors.Green else Color.Black, shape)
                            .clickable(enabled = !busy) { picked = r.folder }.padding(horizontal = 10.dp).testTag("recording-${r.folder}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            BasicText(
                                r.title + (if (r == list.first()) "   (newest)" else ""),
                                style = TextStyle(color = DeskColors.Ink, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                            )
                            BasicText(describe(r), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = DeskColors.InkDim, fontSize = 11.sp))
                        }
                        BasicText(r.durationMs?.let(::formatTime) ?: "", style = TextStyle(color = DeskColors.InkDim, fontSize = 12.sp))
                    }
                }
            }
            OptionRow("DOWNLOAD") {
                for (s in RecordingSelection.entries) {
                    LampButton(s.label, options.selection == s, { if (!busy) onOptions(options.copy(selection = s)) }, DeskColors.Green, Modifier.width(120.dp).height(40.dp).testTag("rec-what-${s.name}"), fontSize = 11.sp)
                }
            }
            OptionRow("FORMAT") {
                for (f in RecordingFormat.entries) {
                    LampButton(f.label, options.format == f, { if (!busy) onOptions(options.copy(format = f)) }, DeskColors.Green, Modifier.width(120.dp).height(40.dp).testTag("rec-format-${f.name}"), fontSize = 11.sp)
                }
                Hint(if (options.format == RecordingFormat.FLOAT32) "As recorded." else if (options.normalise) "A quarter smaller." else "A quarter smaller. Anything recorded over full scale is clipped: NORMALISE avoids it.", modifier = Modifier.weight(1f))
            }
            OptionRow("NORMALISE") {
                LampButton(if (options.normalise) "ON" else "OFF", options.normalise, { if (!busy) onOptions(options.copy(normalise = !options.normalise)) }, DeskColors.Green, Modifier.width(120.dp).height(40.dp).testTag("rec-normalise"), fontSize = 11.sp)
                Hint(
                    if (options.normalise) "The master's loudest peak goes to −1 dB; the tracks all get the same gain, so their balance stays." else "Levels as recorded.",
                    modifier = Modifier.weight(1f),
                )
            }
            Hint(whatGoes(selected, options) + " → ${support.destination}/${selected.folder}", DeskColors.Ink, Modifier.testTag("recordings-summary"))
            if (support.overWifi) {
                Hint(
                    "Through the Pi's Wi-Fi: about ${wifiEstimate(selected, options)}. MASTER is quickest; whole multitracks are better over USB.",
                    DeskColors.Amber, Modifier.testTag("recordings-wifi"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val p = progress
                if (job != null) {
                    LampButton("CANCEL", false, { job?.cancel() }, DeskColors.Red, Modifier.width(120.dp).height(44.dp).testTag("recordings-cancel"))
                    ProgressBar(p, Modifier.weight(1f))
                } else {
                    LampButton(
                        "DOWNLOAD", false,
                        {
                            if (busy) return@LampButton
                            message = null
                            downloaded = null
                            progress = null
                            job = scope.launch {
                                message = try {
                                    val text = support.download(selected, options) { progress = it }
                                    downloaded = selected
                                    text to false
                                } catch (e: CancellationException) {
                                    "Download cancelled." to true
                                } catch (e: Exception) {
                                    (e.message ?: "The download failed") to true
                                }
                                job = null
                            }
                        },
                        DeskColors.Green, Modifier.width(150.dp).height(44.dp).testTag("recordings-download"), fontSize = 12.sp,
                    )
                    downloaded?.takeIf { support.canOpenFolder }?.let { r ->
                        LampButton("OPEN FOLDER", false, { support.openFolder(r) }, DeskColors.Ink, Modifier.width(130.dp).height(44.dp).testTag("recordings-open"), fontSize = 11.sp)
                    }
                    Box(Modifier.weight(1f))
                    if (support.canEject) LampButton(
                        if (ejecting) "EJECTING…" else "EJECT CARD", false,
                        {
                            if (!busy) {
                                ejecting = true
                                scope.launch {
                                    message = try {
                                        (support.eject() ?: "Eject the card in your file manager (or Windows' Safely Remove), then leave file transfer mode on the L6max.") to false
                                    } catch (e: Exception) {
                                        (e.message ?: "Couldn't eject the card") to true
                                    }
                                    ejecting = false
                                }
                            }
                        },
                        DeskColors.Amber, Modifier.width(150.dp).height(44.dp).testTag("recordings-eject"),
                    )
                }
            }
        }
        message?.let { (text, error) -> Hint(text, if (error) DeskColors.Red else DeskColors.Green, Modifier.testTag("recordings-message")) }
    }
}

@Composable
private fun OptionRow(label: String, content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, style = TextStyle(color = DeskColors.InkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp), modifier = Modifier.width(90.dp))
        content()
    }
}

@Composable
private fun ProgressBar(p: DownloadProgress?, modifier: Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.height(44.dp).background(DeskColors.ButtonBottom, shape).border(1.dp, Color.Black, shape).testTag("recordings-progress"), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p?.fraction ?: 0f).background(DeskColors.Green.copy(alpha = 0.45f), shape))
        BasicText(
            p?.let { "${it.file}   ${(it.fraction * 100).toInt()} %" } ?: "Starting…",
            style = TextStyle(color = DeskColors.Ink, fontSize = 12.sp), modifier = Modifier.padding(horizontal = 10.dp).testTag("recordings-progress-text"),
        )
    }
}

/** "CH1, CH5 with sound · 2 master takes · 1.1 GB", or "measuring…" until the tracks have been looked at. */
private fun describe(r: Recording): String {
    val measured = r.tracks.all { it.peakDb != null }
    val used = r.tracks.filter { it.used }
    val tracks = when {
        r.tracks.isEmpty() -> "no tracks"
        !measured -> "${r.tracks.size} tracks, finding which have sound…"
        used.isEmpty() -> "no track has sound"
        else -> used.joinToString(", ") { it.label } + " with sound"
    }
    val masters = when (r.masterTakes) {
        0 -> "no master"
        1 -> "1 master take"
        else -> "${r.masterTakes} master takes"
    }
    return "$tracks  ·  $masters  ·  ${sizeText(r.totalBytes)}"
}

/** What DOWNLOAD will copy, e.g. "MASTER_02 (the last bounce)" or "CH1, CH5 and the master". */
private fun whatGoes(r: Recording, o: RecordingOptions): String {
    val master = r.master
    val masterText = master?.let { "${it.label.lowercase().replaceFirstChar(Char::uppercase)}${if (r.masterTakes > 1) " (the newest)" else ""}" }
    return when (o.selection) {
        RecordingSelection.MASTER -> masterText ?: "No master in this recording"
        RecordingSelection.USED -> {
            val used = r.tracks.filter { it.used }.map { it.label }
            (if (used.isEmpty()) "No tracks" else used.joinToString(", ")) + (masterText?.let { " + $it" } ?: "")
        }
        RecordingSelection.ALL -> "Every file (${sizeText(r.totalBytes)})"
    }
}

/** Roughly how long [o] takes over the Pi's 2.4 GHz Wi-Fi (about 2 MB/s). */
private fun wifiEstimate(r: Recording, o: RecordingOptions): String {
    val files = when (o.selection) {
        RecordingSelection.MASTER -> listOfNotNull(r.master)
        RecordingSelection.USED -> r.tracks.filter { it.used } + listOfNotNull(r.master)
        RecordingSelection.ALL -> r.files
    }
    val read = files.sumOf { it.bytes }
    val bytes = read * (if (o.format == RecordingFormat.PCM24) 3 else 4) / 4
    // Measured on the real Pi: about 3 MB/s over its Wi-Fi, and normalising first reads the files
    // from the card at about 8 MB/s (only the first time: the Pi remembers each file's peak).
    val seconds = bytes / 3_000_000 + if (o.normalise) read / 8_000_000 else 0
    val time = if (seconds < 60) "under a minute" else "${(seconds + 30) / 60} minute${if ((seconds + 30) / 60 == 1L) "" else "s"}"
    return "${sizeText(bytes)}, $time" + if (o.normalise) " (normalising: the Pi reads them first)" else ""
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "${(bytes * 10 / (1L shl 30)) / 10.0} GB"
    else -> "${bytes / (1L shl 20)} MB"
}

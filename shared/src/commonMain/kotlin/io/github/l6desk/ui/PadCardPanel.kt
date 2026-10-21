package io.github.l6desk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import io.github.l6desk.padcard.PadCardSupport
import io.github.l6desk.padcard.PadSound
import io.github.l6desk.player.Track
import io.github.l6desk.player.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PAD SOUNDS (SETUP): the sound pads' sounds on the L6max's memory card. The mixer has to be in
 * its file transfer mode (it then can't mix), connected by USB to this computer or to the Pi
 * bridge; the card then appears like any USB drive and is found by itself. CHOOSE… puts a new sound
 * on a pad (any sound there before is backed up first): from a file dialog, or through the Pi from
 * its music library (picked here, [SongPicker]). REMOVE clears it (two taps). EJECT, then the mixer
 * leaves file transfer mode and plays the new sounds.
 */
@Composable
fun PadCardPanel(support: PadCardSupport, onDismiss: () -> Unit) {
    val card by support.card.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<Int?>(null) } // pad being changed, or 0 for eject
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, is an error
    var picking by remember { mutableStateOf<Int?>(null) } // pad whose sound is being picked from the library
    val library = support.library
    DisposableEffect(support) {
        val stop = support.watch()
        onDispose { stop() }
    }

    fun run(pad: Int, action: suspend () -> String?) {
        if (busy != null) return
        busy = pad
        scope.launch {
            message = try {
                action()?.let { it to false }
            } catch (e: Exception) {
                (e.message ?: "That didn't work") to true
            }
            busy = null
        }
    }

    ModalPanel(
        "PAD SOUNDS", { if (busy == null) onDismiss() }, maxWidth = 720.dp, modifier = Modifier.testTag("pad-card-panel"),
        titleTrailing = {
            LampButton("DONE", false, { if (busy == null) onDismiss() }, DeskColors.Ink, Modifier.width(96.dp).height(40.dp).testTag("pad-card-done"), fontSize = 12.sp)
        },
    ) {
        val c = card
        if (c == null) {
            Hint("The sound pads' sounds live on the L6max's memory card. To change them:", DeskColors.Ink)
            Hint("1.  On the L6max, switch on file transfer mode. While it's on, the mixer doesn't mix or record.")
            Hint("2.  Connect the L6max to ${support.connectTo} by USB. Its card appears here by itself.")
            Hint("Looking for the card…", DeskColors.Amber, Modifier.testTag("pad-card-waiting"))
        } else if (picking != null && library != null) {
            val pad = picking ?: 0
            SongPicker(pad, library.collectAsState().value, onPick = { track -> picking = null; run(pad) { support.chooseTrack(pad, track) } }, onCancel = { picking = null })
        } else {
            Hint("The L6max's card (${c.location}). The mixer plays these sounds once the card is ejected and it leaves file transfer mode.", DeskColors.Green, Modifier.testTag("pad-card-found"))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (pad in c.pads) {
                    PadRow(
                        pad, busy = busy == pad.pad, enabled = busy == null,
                        onChoose = { if (library != null) { if (busy == null) picking = pad.pad } else run(pad.pad) { support.choose(pad.pad) } },
                        onClear = { run(pad.pad) { support.clear(pad.pad) } },
                    )
                }
            }
            Hint("Sounds a pad had before are copied to ${support.backupLocation} first, so nothing recorded on the mixer is lost.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LampButton(
                    if (busy == 0) "EJECTING…" else "EJECT CARD", false, { run(0) { support.eject() ?: "Eject the card in your file manager (or Windows' Safely Remove), then leave file transfer mode on the L6max." } },
                    DeskColors.Amber, Modifier.width(150.dp).height(44.dp).testTag("pad-card-eject"),
                )
                Hint("When you've finished: EJECT first, then switch off file transfer mode on the L6max. Switching it off before ejecting can lose the new sound.", DeskColors.Amber, Modifier.weight(1f))
            }
        }
        message?.let { (text, error) -> Hint(text, if (error) DeskColors.Red else DeskColors.Green, Modifier.testTag("pad-card-message")) }
    }
}

/** Picks a song from the Pi's music library for [pad]: a playlist, then a song in it. */
@Composable
private fun SongPicker(pad: Int, tracks: List<Track>, onPick: (Track) -> Unit, onCancel: () -> Unit) {
    val playlists = tracks.map { it.playlist }.distinct()
    var playlist by remember { mutableStateOf(playlists.firstOrNull()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText("A SOUND FOR PAD $pad", style = TextStyle(color = DeskColors.Pad, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
        LampButton("CANCEL", false, { onCancel() }, DeskColors.Ink, Modifier.width(96.dp).height(40.dp).testTag("pad-pick-cancel"), fontSize = 11.sp)
    }
    if (tracks.isEmpty()) {
        Hint("There are no songs on the Pi yet. Upload one in the PLAYER panel first (short sounds work best on a pad).", DeskColors.Amber, Modifier.testTag("pad-pick-empty"))
        return
    }
    Hint("Songs in the Pi's music library. The pad plays the whole file, so a short sound works best.")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in playlists) {
            LampButton(p, p == playlist, { playlist = p }, DeskColors.Pad, Modifier.width(130.dp).height(40.dp).testTag("pad-pick-playlist-$p"), fontSize = 11.sp, maxLines = 1)
        }
    }
    val shape = RoundedCornerShape(5.dp)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(tracks.filter { it.playlist == playlist }, key = { it.file }) { track ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).background(DeskColors.ButtonBottom, shape).border(1.dp, Color.Black, shape)
                    .clickable { onPick(track) }.padding(horizontal = 10.dp).testTag("pad-pick-song-${track.file}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = DeskColors.Ink, fontSize = 13.sp), modifier = Modifier.weight(1f))
                BasicText(formatTime(track.durationMs), style = TextStyle(color = DeskColors.InkDim, fontSize = 12.sp))
            }
        }
    }
}

@Composable
private fun PadRow(pad: PadSound, busy: Boolean, enabled: Boolean, onChoose: () -> Unit, onClear: () -> Unit) {
    var armed by remember(pad.pad, pad.file) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3_000)
            armed = false
        }
    }
    val shape = RoundedCornerShape(5.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).background(DeskColors.ButtonBottom, shape)
            .border(if (armed) 2.dp else 1.dp, if (armed) DeskColors.Red else Color.Black, shape)
            .padding(horizontal = 10.dp).testTag("pad-row-${pad.pad}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText("PAD ${pad.pad}", style = TextStyle(color = DeskColors.Pad, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = Modifier.width(56.dp))
        BasicText(
            when {
                busy -> "Working…"
                pad.file != null -> pad.file + (pad.durationMs?.let { "   ${formatTime(it)}" } ?: "") + if (pad.others > 0) "   (+${pad.others} other file${if (pad.others == 1) "" else "s"})" else ""
                pad.others > 0 -> "${pad.others} files: the mixer picks one"
                else -> "No sound"
            },
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = if (pad.file != null) DeskColors.Ink else DeskColors.InkDim, fontSize = 13.sp),
            modifier = Modifier.weight(1f).testTag("pad-file-${pad.pad}"),
        )
        LampButton("CHOOSE…", false, { if (enabled) onChoose() }, DeskColors.Pad, Modifier.width(100.dp).height(40.dp).testTag("pad-choose-${pad.pad}"), fontSize = 11.sp)
        if (pad.file != null || pad.others > 0) {
            LampButton(
                if (armed) "SURE?" else "REMOVE", armed, { if (enabled) { if (armed) onClear() else armed = true } }, DeskColors.Red,
                Modifier.width(90.dp).height(40.dp).testTag("pad-clear-${pad.pad}"), fontSize = 10.sp,
            )
        }
    }
}

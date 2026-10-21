package io.github.l6desk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.l6desk.net.RemotePlayer
import io.github.l6desk.player.MAX_VOLUME
import io.github.l6desk.player.PlayerCommand
import io.github.l6desk.player.PlayerOutput
import io.github.l6desk.player.Track
import io.github.l6desk.player.TrackEnd
import io.github.l6desk.player.UploadItem
import io.github.l6desk.player.formatTime
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The bridge's music player (CLAUDE.md, Features): songs on the Pi played into the L6max's USB
 * audio. Tapping a song only cues it; PLAY plays the cued song, so a stray tap can't start music
 * mid-set. If the output channel isn't known to be on USB, PLAY asks first.
 *
 * Songs are in playlists (folders on the Pi). UPLOAD adds files to the end of the playlist shown;
 * EDIT shows ▲ ▼ and DELETE on each song instead of cueing it, so the order can't be changed by
 * accident mid-set, and RENAME and DELETE for the playlist shown (bridges from 0.15). [onUpload] is
 * null where files can't be picked.
 */
@Composable
fun PlayerPanel(
    player: RemotePlayer,
    /** The output channel's USB switch: true, false, or null while unknown. */
    usbInput: (channel: Int) -> Boolean?,
    onCommand: (PlayerCommand) -> Unit,
    /** Where the player plays, as chosen since it was opened (null: not yet; nothing is changed until then). */
    chosenOutput: PlayerOutput?,
    /** Notes [PlayerOutput]'s channel, switches it to USB at full level, and gives the other one back. */
    onChooseOutput: (PlayerOutput) -> Unit,
    onDismiss: () -> Unit,
    uploads: List<UploadItem> = emptyList(),
    onUpload: (() -> Unit)? = null,
    onCancelUpload: (id: Int) -> Unit = {},
    onClearUploads: () -> Unit = {},
    /** Shown when the L6max's USB audio isn't available where the songs are (the Pi, or this computer). */
    noAudioHint: String = "The L6max's USB audio isn't available at the bridge: is the L6max connected to the Pi?",
) {
    val s = player.state
    var confirming by remember { mutableStateOf<String?>(null) } // song waiting for the USB question
    var askedForOutput by remember { mutableStateOf(false) } // PLAY pressed before choosing CH7/CH8
    var editing by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var choosingPlaylist by remember { mutableStateOf(false) }
    val output = chosenOutput ?: s.output
    val onUsb = usbInput(output.channel)
    val songs = player.shown
    // Songs to reorder or delete, or (a bridge from 0.15) a playlist to rename or delete.
    val editable = songs.isNotEmpty() || (s.canEditPlaylists && s.playlist != null)

    fun play(file: String) {
        when {
            chosenOutput == null -> askedForOutput = true
            onUsb == true -> onCommand(PlayerCommand.Play(file))
            else -> confirming = file // switched back on the desk since: ask again
        }
    }

    ModalPanel(
        "PLAYER", onDismiss, maxWidth = 760.dp,
        titleTrailing = {
            LampButton("DONE", false, { onDismiss() }, DeskColors.Ink, Modifier.width(96.dp).height(40.dp).testTag("player-done"), fontSize = 12.sp)
        },
    ) {
        // Output (which channel hears the player) and volume.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Hint("OUTPUT", DeskColors.InkDim)
            for (o in PlayerOutput.entries) {
                val usable = o == PlayerOutput.CH7 || s.usbPairs >= 2
                LampButton(
                    "CH${o.channel} · ${o.usb}", o == chosenOutput, { if (usable) { onChooseOutput(o); askedForOutput = false } }, DeskColors.Pad,
                    Modifier.width(128.dp).height(40.dp).alpha(if (usable) 1f else 0.35f).testTag("player-output-${o.name}"), fontSize = 11.sp,
                )
            }
            Hint("  VOLUME", DeskColors.InkDim)
            VolumeSlider(s.volume, { onCommand(PlayerCommand.SetVolume(it)) }, Modifier.weight(1f))
        }
        when {
            chosenOutput == null -> Hint(
                "Choose where the player plays: CH7 (USB 1/2) or CH8 (USB 3/4). That channel goes to USB at full level; the other is left alone. Both come back as they were when the player closes.",
                if (askedForOutput) DeskColors.Amber else DeskColors.Ink, Modifier.testTag("player-choose-output"),
            )
            onUsb == false -> Hint("CH${output.channel} has been switched back to its own inputs, so the player won't be heard: choose CH${output.channel} again, or switch it to ${output.usb} on the desk.", DeskColors.Amber)
            else -> Unit
        }
        if (s.usbPairs < 2) Hint("On this computer the player can only use USB 1/2 (CH7): Windows offers the L6max's audio as stereo.", DeskColors.InkDim, Modifier.testTag("player-one-pair"))
        if (!s.ready) Hint(noAudioHint.ifEmpty { "The L6max's USB audio isn't available." }, DeskColors.Red)
        s.error?.let { Hint(it, DeskColors.Red, Modifier.testTag("player-error")) }

        // Playlists, and the editing buttons.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistBar(
                s.playlists, s.playlist,
                onSelect = { onCommand(PlayerCommand.SelectPlaylist(it)) },
                onNew = if (player.canUpload) { { naming = true } } else null,
                onShowAll = { choosingPlaylist = true },
                modifier = Modifier.weight(1f),
            )
            if (onUpload != null && player.canUpload && s.playlist != null) {
                LampButton("UPLOAD", false, { onUpload() }, DeskColors.Green, Modifier.width(96.dp).height(40.dp).testTag("player-upload"), description = "Add songs to ${s.playlist}", fontSize = 11.sp)
            }
            if (player.canUpload && editable) {
                LampButton(
                    if (editing) "EDITING" else "EDIT", editing, { editing = !editing }, DeskColors.Amber,
                    Modifier.width(88.dp).height(40.dp).testTag("player-edit"), description = "Reorder or delete songs, rename or delete the playlist", fontSize = 11.sp,
                )
            }
        }
        val shownPlaylist = s.playlist
        if (editing && s.canEditPlaylists && shownPlaylist != null) {
            PlaylistEditRow(
                shownPlaylist, songs.size, playingHere = player.tracks.any { it.file == s.playing && it.playlist == shownPlaylist },
                onRename = { renaming = true },
                onDelete = { onCommand(PlayerCommand.DeletePlaylist(shownPlaylist)) },
            )
        }

        // Songs. The list gives way first when the screen is short, so the transport stays on screen.
        Column(
            Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 264.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when {
                s.playlists.isEmpty() && player.canUpload -> Hint("No playlists yet. Make one with + NEW, then UPLOAD songs into it.")
                s.playlists.isEmpty() -> Hint("No songs yet. Copy WAV files to the Pi's music folder.")
                songs.isEmpty() -> Hint(if (onUpload != null) "No songs in ${s.playlist} yet: UPLOAD adds some." else "No songs in ${s.playlist} yet.")
            }
            songs.forEachIndexed { i, t ->
                if (editing) {
                    EditRow(
                        t, first = i == 0, last = i == songs.lastIndex, playing = t.file == s.playing,
                        onMove = { by -> onCommand(PlayerCommand.Move(t.file, by)) },
                        onDelete = { onCommand(PlayerCommand.Delete(t.file)) },
                        onRename = if (s.canEditPlaylists) { title -> onCommand(PlayerCommand.Rename(t.file, title)) } else null,
                    )
                } else {
                    TrackRow(
                        t, playing = t.file == s.playing, cued = t.file == s.cued,
                        onCue = { onCommand(PlayerCommand.Cue(t.file)) },
                        onEnd = { onCommand(PlayerCommand.SetEnd(t.file, it)) },
                    )
                }
            }
        }

        if (uploads.isNotEmpty()) Uploads(uploads, onCancelUpload, onClearUploads)

        // Progress and transport.
        val current = player.tracks.firstOrNull { it.file == s.playing }
        if (current != null) {
            Progress(s.positionMs, current.durationMs)
            BasicText(
                "${if (s.paused) "Paused" else "Playing"}: ${current.title}   ${formatTime(s.positionMs)} / ${formatTime(current.durationMs)}" +
                    if (current.playlist != s.playlist && current.playlist.isNotEmpty()) "   (${current.playlist})" else "",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = DeskColors.Pad, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.testTag("player-now"),
            )
        }
        val waiting = confirming
        if (waiting != null) {
            Hint(
                if (onUsb == false) "CH${output.channel} is on its own inputs: switch it to ${output.usb}?"
                else "CH${output.channel} may not be on ${output.usb}: switch it to be sure?",
                DeskColors.Amber,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LampButton("SWITCH TO USB & PLAY", true, {
                    onChooseOutput(output)
                    onCommand(PlayerCommand.Play(waiting))
                    confirming = null
                }, DeskColors.Pad, Modifier.width(200.dp).height(44.dp).testTag("player-switch-play"), fontSize = 11.sp)
                LampButton("PLAY ANYWAY", false, {
                    onCommand(PlayerCommand.Play(waiting))
                    confirming = null
                }, DeskColors.Pad, Modifier.width(130.dp).height(44.dp).testTag("player-play-anyway"), fontSize = 11.sp)
                LampButton("CANCEL", false, { confirming = null }, DeskColors.Ink, Modifier.width(100.dp).height(44.dp), fontSize = 11.sp)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (current == null) {
                    val cued = songs.firstOrNull { it.file == s.cued }
                    LampButton(
                        if (cued != null) "▶ PLAY" else "PLAY", false, { cued?.let { play(it.file) } }, DeskColors.Green,
                        Modifier.width(140.dp).height(48.dp).testTag("player-play"), description = "Play the cued song", fontSize = 13.sp,
                    )
                    Hint(cued?.let { "Cued: ${it.title}" } ?: "Tap a song to cue it.", DeskColors.Ink)
                } else {
                    LampButton(
                        if (s.paused) "▶ RESUME" else "❚❚ PAUSE", s.paused,
                        { onCommand(if (s.paused) PlayerCommand.Resume else PlayerCommand.Pause) }, DeskColors.Amber,
                        Modifier.width(140.dp).height(48.dp).testTag("player-pause"), fontSize = 13.sp,
                    )
                    LampButton(
                        "■ STOP", false, { onCommand(PlayerCommand.Stop) }, DeskColors.Red,
                        Modifier.width(120.dp).height(48.dp).testTag("player-stop"), fontSize = 13.sp,
                    )
                }
            }
        }
    }
    // A song that stopped meanwhile needs no question.
    LaunchedEffect(s.playing) { if (s.playing != null) confirming = null }
    // Nothing left to edit (e.g. the last song or playlist deleted): back to normal.
    LaunchedEffect(editable) { if (!editable) editing = false }

    if (choosingPlaylist) {
        PlaylistChooser(
            s.playlists, s.playlist,
            onSelect = { name ->
                onCommand(PlayerCommand.SelectPlaylist(name))
                choosingPlaylist = false
            },
            onNew = if (player.canUpload) { { choosingPlaylist = false; naming = true } } else null,
            onDismiss = { choosingPlaylist = false },
        )
    }
    if (naming) {
        PlaylistNameDialog(
            title = "NEW PLAYLIST", initial = "", confirm = "CREATE",
            onConfirm = { name ->
                onCommand(PlayerCommand.CreatePlaylist(name))
                naming = false
            },
            onDismiss = { naming = false },
        )
    }
    val renamingPlaylist = s.playlist
    if (renaming && renamingPlaylist != null) {
        PlaylistNameDialog(
            title = "RENAME PLAYLIST", initial = renamingPlaylist, confirm = "RENAME",
            onConfirm = { name ->
                if (name != renamingPlaylist) onCommand(PlayerCommand.RenamePlaylist(renamingPlaylist, name))
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }
}

@Composable
private fun TrackRow(track: Track, playing: Boolean, cued: Boolean, onCue: () -> Unit, onEnd: (TrackEnd) -> Unit) {
    SongRow(
        track, playing, highlight = if (playing) DeskColors.Pad else if (cued) DeskColors.Amber else null,
        modifier = Modifier
            // Taps via pointerInput, not clickable, so the row's end button stays its own control.
            .pointerInput(track.file) { detectTapGestures { onCue() } }
            .semantics { onClick(label = "Cue ${track.title}") { onCue(); true } },
    ) {
        // End setting: tap to cycle Stop → Play next → Loop.
        val next = TrackEnd.entries[(track.end.ordinal + 1) % TrackEnd.entries.size]
        LampButton(
            track.end.label.uppercase(), track.end != TrackEnd.STOP, { onEnd(next) }, DeskColors.Amber,
            Modifier.width(96.dp).height(34.dp).testTag("track-end-${track.file}"),
            description = "At the end: ${track.end.label} (tap to change)", fontSize = 9.sp,
        )
    }
}

/**
 * A song while editing: move it up or down, delete it (a second tap within 3 s confirms), or type
 * over its title to rename it ([onRename]; null for a bridge older than 0.15).
 */
@Composable
private fun EditRow(
    track: Track,
    first: Boolean,
    last: Boolean,
    playing: Boolean,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onRename: ((String) -> Unit)? = null,
) {
    var armed by remember(track.file) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3_000)
            armed = false
        }
    }
    SongRow(track, playing, highlight = if (armed) DeskColors.Red else null, onRename = onRename) {
        LampButton("▲", false, { if (!first) onMove(-1) }, DeskColors.Ink, Modifier.width(48.dp).height(36.dp).testTag("track-up-${track.file}"), description = "Move ${track.title} up", fontSize = 13.sp)
        LampButton("▼", false, { if (!last) onMove(1) }, DeskColors.Ink, Modifier.width(48.dp).height(36.dp).testTag("track-down-${track.file}"), description = "Move ${track.title} down", fontSize = 13.sp)
        LampButton(
            if (armed) "SURE?" else "DELETE", armed, { if (armed) onDelete() else armed = true }, DeskColors.Red,
            Modifier.width(80.dp).height(36.dp).testTag("track-delete-${track.file}"),
            description = if (armed) "Tap again to delete ${track.title}" else "Delete ${track.title}", fontSize = 10.sp,
        )
    }
}

@Composable
private fun SongRow(
    track: Track,
    playing: Boolean,
    highlight: Color?,
    modifier: Modifier = Modifier,
    /** Makes the title editable in place: sent on Enter / Done or when the field loses focus. */
    onRename: ((String) -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .background(if (playing) Color(0xFF173236) else DeskColors.ButtonBottom, shape)
            .border(if (highlight != null) 2.dp else 1.dp, highlight ?: Color.Black, shape)
            .padding(horizontal = 10.dp)
            .testTag("track-${track.file}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText(if (playing) "▶" else " ", style = TextStyle(color = DeskColors.Pad, fontSize = 14.sp, fontWeight = FontWeight.Bold), modifier = Modifier.width(14.dp))
        if (onRename != null) {
            TitleField(track, onRename, Modifier.weight(1f))
        } else {
            BasicText(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = DeskColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
        }
        BasicText(formatTime(track.durationMs), style = TextStyle(color = DeskColors.InkDim, fontSize = 12.sp))
        trailing()
    }
}

/**
 * A song's title, typed over to rename it. The new title is sent on Enter / Done, or when the
 * field loses focus (tapping elsewhere); Escape, or an empty title, puts the old one back.
 */
@Composable
private fun TitleField(track: Track, onRename: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(track.file, track.title) { mutableStateOf(track.title) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    fun commit() {
        val title = text.trim()
        if (title.isEmpty()) text = track.title else if (title != track.title) onRename(title)
    }
    // Leaving EDIT mode while typing still keeps the new title.
    val latestCommit by rememberUpdatedState(::commit)
    DisposableEffect(Unit) { onDispose { if (focused) latestCommit() } }
    val shape = RoundedCornerShape(4.dp)
    BasicTextField(
        value = text,
        onValueChange = { text = it.take(100).replace("\n", "") },
        singleLine = true,
        textStyle = TextStyle(color = DeskColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        cursorBrush = SolidColor(DeskColors.Amber),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier
            .background(if (focused) Color(0xFF15161A) else Color.Transparent, shape)
            .border(1.dp, if (focused) DeskColors.Amber else Color(0xFF3A3D42), shape)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .onFocusChanged { state ->
                if (focused && !state.isFocused) commit()
                focused = state.isFocused
            }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    text = track.title
                    focusManager.clearFocus()
                    true
                } else {
                    false
                }
            }
            .semantics { contentDescription = "Title of ${track.title} (type to rename)" }
            .testTag("track-title-${track.file}"),
    )
}

@Composable
private fun Uploads(items: List<UploadItem>, onCancel: (Int) -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint("UPLOADS", DeskColors.InkDim, Modifier.weight(1f))
            if (items.any { it.finished }) {
                LampButton("CLEAR", false, { onClear() }, DeskColors.Ink, Modifier.width(72.dp).height(30.dp).testTag("uploads-clear"), description = "Clear finished uploads")
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 96.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (item in items) {
                Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).testTag("upload-${item.id}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText(
                        "${item.name} → ${item.playlist}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = DeskColors.Ink, fontSize = 11.sp), modifier = Modifier.weight(1f),
                    )
                    val (text, color) = when (item.stage) {
                        UploadItem.Stage.WAITING -> "Waiting" to DeskColors.InkDim
                        UploadItem.Stage.SENDING -> (item.fraction?.let { "Sending ${(it * 100).roundToInt()}%" } ?: "Sending…") to DeskColors.Pad
                        UploadItem.Stage.CONVERTING -> "Converting…" to DeskColors.Pad
                        UploadItem.Stage.DONE -> "Added" to DeskColors.Green
                        UploadItem.Stage.FAILED -> (item.message ?: "Failed") to DeskColors.Red
                        UploadItem.Stage.CANCELLED -> "Cancelled" to DeskColors.InkDim
                    }
                    BasicText(
                        text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.widthIn(max = 320.dp).testTag("upload-state-${item.id}"),
                    )
                    if (item.stage == UploadItem.Stage.WAITING || item.stage == UploadItem.Stage.SENDING) {
                        LampButton("CANCEL", false, { onCancel(item.id) }, DeskColors.Ink, Modifier.width(72.dp).height(30.dp).testTag("upload-cancel-${item.id}"), description = "Cancel ${item.name}")
                    }
                }
            }
        }
    }
}

/**
 * The playlists, one tap each. When there are more than fit, the row shows as many as it can
 * (always including the one shown) and ALL (n) opens a list of every playlist, with + NEW there.
 */
@Composable
private fun PlaylistBar(
    names: List<String>,
    shown: String?,
    onSelect: (String) -> Unit,
    onNew: (() -> Unit)?,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val gap = 6.dp
        val chip = 128.dp
        val newWidth = 80.dp
        val allWidth = 96.dp
        val fitsAll = (chip + gap) * names.size + (if (onNew != null) newWidth else 0.dp) <= maxWidth
        val visible = if (fitsAll) {
            names
        } else {
            val n = ((maxWidth - allWidth) / (chip + gap)).toInt().coerceAtLeast(1)
            val first = names.take(n)
            if (shown != null && shown !in first) first.take(n - 1) + shown else first
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            for (name in visible) {
                LampButton(
                    name, name == shown, { onSelect(name) }, DeskColors.Amber,
                    Modifier.width(chip).height(40.dp).testTag("playlist-$name"),
                    description = "Playlist $name", fontSize = 11.sp, maxLines = 1,
                )
            }
            if (fitsAll) {
                if (onNew != null) {
                    LampButton("+ NEW", false, { onNew() }, DeskColors.Ink, Modifier.width(newWidth).height(40.dp).testTag("playlist-new"), description = "New playlist", fontSize = 11.sp)
                }
            } else {
                LampButton(
                    "ALL (${names.size})", false, { onShowAll() }, DeskColors.Amber,
                    Modifier.width(allWidth).height(40.dp).testTag("playlist-all"), description = "All ${names.size} playlists", fontSize = 11.sp,
                )
            }
        }
    }
}

/** Every playlist, for when there are more than fit in the row: tap one to show it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaylistChooser(names: List<String>, shown: String?, onSelect: (String) -> Unit, onNew: (() -> Unit)?, onDismiss: () -> Unit) {
    ModalPanel(
        "PLAYLISTS", onDismiss, maxWidth = 900.dp, modifier = Modifier.testTag("playlist-chooser"),
        titleTrailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onNew != null) {
                    LampButton("+ NEW", false, { onNew() }, DeskColors.Ink, Modifier.width(96.dp).height(40.dp).testTag("playlist-new"), description = "New playlist", fontSize = 12.sp)
                }
                LampButton("CLOSE", false, { onDismiss() }, DeskColors.Ink, Modifier.width(96.dp).height(40.dp).testTag("playlist-chooser-close"), fontSize = 12.sp)
            }
        },
    ) {
        FlowRow(
            Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (name in names) {
                LampButton(
                    name, name == shown, { onSelect(name) }, DeskColors.Amber,
                    Modifier.width(200.dp).height(52.dp).testTag("playlist-choose-$name"),
                    description = "Playlist $name", fontSize = 12.sp, maxLines = 2,
                )
            }
        }
    }
}

/**
 * While editing: the playlist shown can be renamed, or deleted with all its songs (a second tap
 * within 3 s confirms, and the warning says how many songs go, and that the one playing stops).
 */
@Composable
private fun PlaylistEditRow(name: String, songCount: Int, playingHere: Boolean, onRename: () -> Unit, onDelete: () -> Unit) {
    var armed by remember(name) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3_000)
            armed = false
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Hint(
            if (armed) {
                "Delete \"$name\"" + (if (songCount > 0) " and its ${if (songCount == 1) "song" else "$songCount songs"}" else "") +
                    " from the Pi? It can't be undone." + if (playingHere) " The song playing stops." else ""
            } else {
                "Playlist \"$name\"" + if (songCount > 0) "  ·  Tap a song's title to rename it." else ""
            },
            if (armed) DeskColors.Red else DeskColors.Ink,
            Modifier.weight(1f).testTag("playlist-edit-hint"),
        )
        LampButton(
            "RENAME", false, { onRename() }, DeskColors.Ink,
            Modifier.width(96.dp).height(40.dp).testTag("playlist-rename"), description = "Rename $name", fontSize = 11.sp,
        )
        LampButton(
            if (armed) "SURE? DELETE" else "DELETE PLAYLIST", armed, { if (armed) onDelete() else armed = true }, DeskColors.Red,
            Modifier.width(150.dp).height(40.dp).testTag("playlist-delete"),
            description = if (armed) "Tap again to delete $name" else "Delete $name", fontSize = 10.sp,
        )
    }
}

/** A playlist name: for a new playlist, or renaming the one shown ([initial]). */
@Composable
private fun PlaylistNameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun done() {
        if (text.isNotBlank()) onConfirm(text.trim())
    }
    ModalPanel(title, onDismiss, maxWidth = 420.dp) {
        DeskTextField(
            value = text,
            onValueChange = { text = it.take(60) },
            onDone = ::done,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("playlist-name"),
        )
        Hint("A name for the set or show, e.g. \"Saturday gig\". Songs you upload go into the playlist shown.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            LampButton("CANCEL", false, { onDismiss() }, DeskColors.Ink, Modifier.width(90.dp).height(40.dp))
            LampButton(confirm, false, { done() }, DeskColors.Green, Modifier.width(90.dp).height(40.dp).testTag("playlist-create"))
        }
    }
}

/**
 * The player's volume, 0–100 % of the files' own level: drag or tap along it. While the finger is
 * down the slider shows its own value (the bridge's echo lags behind); each whole step is sent.
 */
@Composable
private fun VolumeSlider(volume: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = dragging ?: volume
    val change by rememberUpdatedState(onChange)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(
            Modifier.weight(1f).height(48.dp)
                .pointerInput(Unit) {
                    fun at(x: Float) = (x / size.width * MAX_VOLUME).roundToInt().coerceIn(0, MAX_VOLUME)
                    fun set(v: Int) {
                        if (v != dragging) {
                            dragging = v
                            change(v)
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        set(at(down.position.x))
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { if (it.pressed) { set(at(it.position.x)); it.consume() } }
                        } while (event.changes.any { it.pressed })
                        dragging = null
                    }
                }
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), 0f..MAX_VOLUME.toFloat(), MAX_VOLUME - 1)
                    setProgress { v -> change(v.roundToInt().coerceIn(0, MAX_VOLUME)); true }
                }
                .testTag("player-volume"),
            contentAlignment = Alignment.CenterStart,
        ) {
            val fraction = shown / MAX_VOLUME.toFloat()
            Box(Modifier.fillMaxWidth().height(8.dp).background(DeskColors.Slot, RoundedCornerShape(4.dp))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(DeskColors.Pad, RoundedCornerShape(4.dp)))
            }
            Box(
                Modifier.offset(x = (maxWidth - 20.dp) * fraction).width(20.dp).height(36.dp)
                    .background(DeskColors.ButtonBottom, RoundedCornerShape(4.dp))
                    .border(2.dp, DeskColors.Pad, RoundedCornerShape(4.dp)),
            )
        }
        BasicText(
            "$shown%", style = TextStyle(color = DeskColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.width(44.dp).testTag("player-volume-value"),
        )
    }
}

@Composable
private fun Progress(positionMs: Long, durationMs: Long) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().height(6.dp).background(DeskColors.Slot, RoundedCornerShape(3.dp))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(DeskColors.Pad, RoundedCornerShape(3.dp)))
    }
}

/** Top bar: "▶ Song  2:41 left" while the player plays; tap to open the panel. */
@Composable
fun NowPlaying(player: RemotePlayer, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = player.state
    val track = player.tracks.firstOrNull { it.file == s.playing } ?: return
    val left = (track.durationMs - s.positionMs).coerceAtLeast(0)
    BasicText(
        "${if (s.paused) "❚❚" else "▶"} ${track.title}  ${formatTime(left)} left",
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = DeskColors.Pad, fontSize = 12.sp, fontWeight = FontWeight.Bold),
        modifier = modifier.widthIn(max = 260.dp).clickable(onClick = onClick).testTag("now-playing"),
    )
}

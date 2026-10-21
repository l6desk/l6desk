package io.github.l6desk.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.l6desk.midi.MidiMessage
import io.github.l6desk.model.CHANNEL_COUNT
import io.github.l6desk.model.CcMap
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.Control
import io.github.l6desk.model.L6maxLayout
import io.github.l6desk.model.Profile
import io.github.l6desk.net.BridgeStatus
import io.github.l6desk.persistence.AppSettings
import io.github.l6desk.persistence.ProfileLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/** The CC map rows the editor shows: the controls the desk uses. Other entries keep their values. */
private val MAP_ROWS = listOf(
    ChannelParam.EQ_HIGH, ChannelParam.EQ_MID_FREQ, ChannelParam.EQ_MID, ChannelParam.EQ_LOW,
    ChannelParam.PAN, ChannelParam.LEVEL, ChannelParam.MUTE, ChannelParam.MONO_X2,
)

private val CELL_W = 58.dp
private val LABEL_W = 70.dp

/** Width at which the panel switches from one scrolling column to two side-by-side columns. */
private val TWO_COLUMN_WIDTH = 820.dp

/**
 * Setup: CC-map profiles, the map editor with Learn and "read from mixer", and options.
 * Editing the factory profile first copies it to a new profile, so the factory map stays intact.
 * Wide screens show the map on the left and everything else on the right, so nothing needs
 * scrolling; narrow screens (portrait) stack it all in one scrolling column.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupPanel(
    library: ProfileLibrary,
    settings: AppSettings,
    connected: Boolean,
    onLibraryChange: (ProfileLibrary) -> Unit,
    onSettingsChange: (AppSettings) -> Unit,
    readMap: suspend () -> CcMap,
    learn: suspend () -> MidiMessage.ControlChange,
    onDismiss: () -> Unit,
    /** Learn for a sound pad: the next Note On from the mixer (a pad pressed on it). */
    learnNote: suspend () -> MidiMessage.NoteOn = { awaitCancellation() },
    /** Desktop only: the Wi-Fi bridge's status; null hides Bridge mode. */
    bridgeStatus: BridgeStatus? = null,
    /** "RESET MIX": returns false if it couldn't (not connected). Null hides the section. */
    onResetMix: (() -> Boolean)? = null,
    /** "REPORT A PROBLEM", next to DONE. Null hides it. */
    onReport: (() -> Unit)? = null,
    /** "PAD SOUNDS…": the pads' sounds on the mixer's card (desktop). Null hides it. */
    onPadSounds: (() -> Unit)? = null,
    onRecordings: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Control.Channel?>(null) }
    var selectedPad by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<Job?>(null) }
    var learning by remember { mutableStateOf(false) }
    var savingAs by remember { mutableStateOf<String?>(null) }
    var confirmingReset by remember { mutableStateOf(false) }
    val active = library.active

    fun say(text: String, error: Boolean = false) {
        status = text
        statusIsError = error
    }

    /** Applies [change] to the active profile, copying the factory profile first if needed. */
    fun editActive(change: (Profile) -> Profile) {
        var target = active
        if (target.name == Profile.FACTORY.name) {
            val name = generateSequence(1) { it + 1 }.map { if (it == 1) "My L6max" else "My L6max $it" }
                .first { n -> library.profiles.none { it.name == n } }
            target = target.copy(name = name, verified = false)
        }
        onLibraryChange(library.save(change(target)))
    }

    fun nextAssignable(cc: Int?, step: Int): Int {
        var c = (cc ?: if (step > 0) 0 else 120) + step
        while (c in 0..127 && !L6maxLayout.isAssignableCc(c)) c += if (step > 0) 1 else -1
        return c.coerceIn(1, 119).let { if (L6maxLayout.isAssignableCc(it)) it else cc ?: 1 }
    }

    fun readFromMixer() {
        busy = scope.launch {
            say("Reading the map from the L6max…")
            try {
                val map = readMap()
                if (map == active.ccMap) {
                    say("Read ${map.assignments.size} assignments: they match this profile.")
                    if (!active.verified && active.name != Profile.FACTORY.name) editActive { it.copy(verified = true) }
                } else {
                    editActive { it.copy(ccMap = map, verified = true) }
                    say("Read ${map.assignments.size} assignments from the mixer.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Couldn't read the map: ${e.message ?: e::class.simpleName}", error = true)
            } finally {
                busy = null
            }
        }
    }

    fun startLearn(cell: Control.Channel) {
        busy = scope.launch {
            var target: Control.Channel? = cell
            learning = true
            try {
                while (target != null) {
                    val t = target
                    selected = t
                    say("Move $t on the L6max… (the next control you move is assigned)")
                    val msg = learn()
                    if (!L6maxLayout.isAssignableCc(msg.controller)) {
                        say("CC ${msg.controller} can't be assigned on the L6max.", error = true)
                        break
                    }
                    editActive { it.copy(ccMap = it.ccMap.with(t, msg.controller), midiChannel = msg.channel) }
                    // Move on to the same control on the next channel, ready for the next turn.
                    target = (t.channel + 1..CHANNEL_COUNT).firstOrNull { t.param.availableOn(it) }?.let { Control.Channel(it, t.param) }
                    if (target == null) say("Learned $t = CC ${msg.controller}. Done.")
                }
            } finally {
                learning = false
                busy = null
            }
        }
    }

    fun startLearnPad(pad: Int) {
        busy = scope.launch {
            var target: Int? = pad
            learning = true
            try {
                while (target != null) {
                    val t = target
                    selectedPad = t
                    say("Press sound pad $t on the L6max…")
                    val msg = learnNote()
                    editActive { it.copy(ccMap = it.ccMap.withPadNote(t, msg.note), midiChannel = msg.channel) }
                    // Move on to the next pad, ready for the next press.
                    target = (t + 1).takeIf { it <= L6maxLayout.PAD_COUNT }
                    if (target == null) say("Learned pad $t = note ${msg.note}. Done.")
                }
            } finally {
                learning = false
                busy = null
            }
        }
    }

    // ---- Sections ------------------------------------------------------------------------

    @Composable
    fun mapSection(cell: Dp = CELL_W) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("MIDI MAP")
            Box(Modifier.width(8.dp))
            Hint("MIDI channel")
            SmallButton("−", enabled = active.midiChannel > 1) { editActive { it.copy(midiChannel = it.midiChannel - 1) } }
            BasicText(
                "${active.midiChannel}",
                style = TextStyle(color = DeskColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.testTag("midi-channel"),
            )
            SmallButton("+", enabled = active.midiChannel < 16) { editActive { it.copy(midiChannel = it.midiChannel + 1) } }
            Box(Modifier.width(8.dp))
            SmallButton("READ FROM MIXER", enabled = connected && busy == null, tag = "read-map") { readFromMixer() }
        }
        MapGrid(active.ccMap, active.ccMap.duplicates().keys, selected, cell) {
            selected = if (selected == it) null else it
            selectedPad = null
        }
        PadRow(active.ccMap.padNotes, selectedPad, cell) {
            selectedPad = if (selectedPad == it) null else it
            selected = null
        }
    }

    @Composable
    fun profileSection() = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("CC MAP PROFILE")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in library.profiles) {
                Chip(
                    text = p.name + if (p.verified) " ✓" else "",
                    selected = p.name == active.name,
                    onClick = { onLibraryChange(library.withActive(p.name)) },
                    modifier = Modifier.testTag("profile-${p.name}"),
                )
            }
        }
        val asName = savingAs
        if (asName != null) {
            DeskTextField(asName, { savingAs = it.take(24) }, onDone = {}, modifier = Modifier.fillMaxWidth().testTag("profile-name"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("SAVE", enabled = asName.isNotBlank() && asName.trim() != Profile.FACTORY.name, tag = "profile-save") {
                    onLibraryChange(library.save(active.copy(name = asName.trim())))
                    savingAs = null
                    say("Saved as \"${asName.trim()}\".")
                }
                SmallButton("CANCEL") { savingAs = null }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("SAVE AS…", tag = "profile-save-as") { savingAs = "" }
                SmallButton("DELETE", enabled = active.name != Profile.FACTORY.name) {
                    onLibraryChange(library.delete(active.name))
                    say("Deleted \"${active.name}\".")
                }
                SmallButton("FACTORY MAP", enabled = active.ccMap != L6maxLayout.FACTORY_CC_MAP || active.midiChannel != 1) {
                    editActive { it.copy(ccMap = L6maxLayout.FACTORY_CC_MAP, midiChannel = 1) }
                    say("Factory map restored.")
                }
            }
        }
    }

    @Composable
    fun cellSection() = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val cell = selected
        val pad = selectedPad
        if (pad != null) {
            val note = active.ccMap.padNote(pad)
            Hint("Sound pad $pad = ${note?.let { "note $it" } ?: "not mapped"}", DeskColors.Ink)
            Hint("The pad's play mode is set on the L6max. One-shot pads can't be stopped from the app: use Loop or Hold for anything you may need to stop.")
            fun step(by: Int) = editActive { it.copy(ccMap = it.ccMap.withPadNote(pad, ((note ?: 60) + by).coerceIn(0, 127))) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallButton("−12") { step(-12) }
                SmallButton("−1") { step(-1) }
                SmallButton("+1") { step(1) }
                SmallButton("+12") { step(12) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("CLEAR") { editActive { it.copy(ccMap = it.ccMap.withPadNote(pad, null)) } }
                if (learning) {
                    SmallButton("CANCEL LEARN", tag = "learn-cancel") { busy?.cancel(); say("Learn cancelled.") }
                } else {
                    SmallButton("LEARN", enabled = connected && busy == null, tag = "learn") { startLearnPad(pad) }
                }
            }
        } else if (cell == null) {
            Hint("Tap a cell to change it or learn it. Duplicate numbers are shown in red.")
        } else {
            val cc = active.ccMap[cell]
            Hint("$cell = ${cc ?: "not mapped"}", DeskColors.Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallButton("−10") { editActive { it.copy(ccMap = it.ccMap.with(cell, nextAssignable(cc, -10))) } }
                SmallButton("−1") { editActive { it.copy(ccMap = it.ccMap.with(cell, nextAssignable(cc, -1))) } }
                SmallButton("+1") { editActive { it.copy(ccMap = it.ccMap.with(cell, nextAssignable(cc, 1))) } }
                SmallButton("+10") { editActive { it.copy(ccMap = it.ccMap.with(cell, nextAssignable(cc, 10))) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("CLEAR") { editActive { it.copy(ccMap = it.ccMap.with(cell, null)) } }
                if (learning) {
                    SmallButton("CANCEL LEARN", tag = "learn-cancel") { busy?.cancel(); say("Learn cancelled.") }
                } else {
                    SmallButton("LEARN", enabled = connected && busy == null, tag = "learn") { startLearn(cell) }
                }
            }
        }
        if (status.isNotEmpty()) Hint(status, if (statusIsError) DeskColors.Red else DeskColors.Green, Modifier.testTag("setup-status"))
    }

    @Composable
    fun optionsSection() = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("OPTIONS")
        OptionRow("Send the desk to the mixer as soon as it connects", settings.autoPushOnConnect, "option-autopush") {
            onSettingsChange(settings.copy(autoPushOnConnect = it))
        }
        OptionRow("Show the MIDI log", settings.showMidiLog, "option-midilog") { onSettingsChange(settings.copy(showMidiLog = it)) }
        OptionRow("The mixer's scene buttons recall the app's scenes", settings.linkMixerScenes, "option-link-scenes") {
            onSettingsChange(settings.copy(linkMixerScenes = it))
        }
        OptionRow("Closing the app shuts down the Pi bridge", settings.powerOffBridgeOnExit, "option-power-off") {
            onSettingsChange(settings.copy(powerOffBridgeOnExit = it))
        }
        if (bridgeStatus != null) {
            OptionRow("Bridge mode: let a tablet control this mixer over Wi-Fi", settings.bridgeMode, "option-bridge") {
                onSettingsChange(settings.copy(bridgeMode = it))
            }
            if (settings.bridgeMode) {
                Hint(bridgeSummary(bridgeStatus), if (bridgeStatus.error != null) DeskColors.Red else DeskColors.Green, Modifier.testTag("bridge-status"))
                BridgePinRow(settings.bridgeModePin) { onSettingsChange(settings.copy(bridgeModePin = it)) }
            }
        }
    }

    /** PAD SOUNDS and RECORDINGS: the mixer's memory card, in its file transfer mode. */
    @Composable
    fun cardSection() = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onPadSounds == null && onRecordings == null) return@Column
        SectionTitle("THE MIXER'S CARD")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onPadSounds != null) SmallButton("PAD SOUNDS…", tag = "pad-sounds") { onPadSounds() }
            if (onRecordings != null) SmallButton("RECORDINGS…", tag = "recordings") { onRecordings() }
            Hint(
                listOfNotNull("the sound pads' sounds".takeIf { onPadSounds != null }, "download its recordings".takeIf { onRecordings != null })
                    .joinToString("; ").replaceFirstChar(Char::uppercase) + ". The L6max goes into file transfer mode meanwhile.",
                modifier = Modifier.weight(1f),
            )
        }
    }

    @Composable
    fun newMixSection() = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val reset = onResetMix ?: return@Column
        SectionTitle("NEW MIX")
        if (!confirmingReset) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallButton("RESET MIX…", enabled = connected, tag = "reset-mix") { confirmingReset = true }
                Hint(if (connected) "A clean slate on the desk and the L6max." else "Connect to the mixer first.", modifier = Modifier.weight(1f))
            }
        } else {
            Hint(
                "Reset the mix? Every level goes to 0, pan to centre, EQ flat, EFX sends to 0, mutes and solos off, " +
                    "master to 6. Channel names, MONO ×2 and AUX sends are kept.",
                DeskColors.Amber,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("CANCEL", tag = "reset-mix-cancel") { confirmingReset = false }
                SmallButton("RESET MIX", tag = "reset-mix-confirm") {
                    confirmingReset = false
                    if (reset()) say("Mix reset: all levels at 0. Bring channels up when ready.") else say("Not connected: nothing was reset.", error = true)
                }
            }
        }
    }

    // ---- Layout ----------------------------------------------------------------------------

    ModalPanel(
        "SETUP  ·  ${io.github.l6desk.AppInfo.NAME} ${io.github.l6desk.AppInfo.VERSION}", onDismiss, maxWidth = 960.dp, modifier = Modifier.testTag("setup-panel"),
        titleTrailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onReport != null) {
                    LampButton(
                        "REPORT A PROBLEM", false, { onReport() }, DeskColors.Ink,
                        Modifier.width(170.dp).height(36.dp).testTag("setup-report"), description = "Report a problem",
                    )
                }
                LampButton("DONE", false, { onDismiss() }, DeskColors.Ink, Modifier.width(90.dp).height(36.dp).testTag("setup-done"))
            }
        },
    ) {
        BoxWithConstraints(Modifier.weight(1f, fill = false)) {
            if (maxWidth >= TWO_COLUMN_WIDTH) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    // As wide as the map; the card section's hint wraps under it.
                    Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Min).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        mapSection()
                        cardSection()
                    }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        // Tight enough that all of it fits on the tablet, even with a cell's editor shown.
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        profileSection()
                        cellSection()
                        optionsSection()
                        newMixSection()
                    }
                }
            } else {
                // One column (portrait): the map's cells narrow to fit (never below a finger's width).
                val cell = ((maxWidth - LABEL_W - 3.dp * CHANNEL_COUNT) / CHANNEL_COUNT).coerceIn(44.dp, CELL_W)
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    profileSection()
                    mapSection(cell)
                    cellSection()
                    optionsSection()
                    newMixSection()
                    cardSection()
                }
            }
        }
    }
}

/** One or two lines describing the bridge for Setup. */
/** Bridge mode's PIN: applied as soon as it's 4 to 8 digits, or empty (no PIN). */
@Composable
private fun BridgePinRow(pin: String, onChange: (String) -> Unit) {
    var text by remember(pin) { mutableStateOf(pin) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Hint("PIN for tablets")
        DeskTextField(
            value = text,
            onValueChange = { v ->
                text = v.filter { it.isDigit() }.take(8)
                if (text.isEmpty() || io.github.l6desk.net.BridgePin.isValid(text)) onChange(text)
            },
            onDone = {},
            modifier = Modifier.width(130.dp).testTag("bridge-pin"),
        )
        Hint(
            when {
                text.isEmpty() -> "None: any tablet on the network can connect."
                io.github.l6desk.net.BridgePin.isValid(text) -> "Tablets must give it to connect."
                else -> "4 to 8 digits."
            },
            if (text.isNotEmpty() && !io.github.l6desk.net.BridgePin.isValid(text)) DeskColors.Amber else DeskColors.InkDim,
            Modifier.weight(1f),
        )
    }
}

fun bridgeSummary(b: BridgeStatus): String = when (val error = b.error) {
    null -> bridgeRunningSummary(b)
    else -> error
}

private fun bridgeRunningSummary(b: BridgeStatus): String = when {
    !b.running -> "Starting…"
    else -> {
        val where = b.addresses.ifEmpty { listOf("this computer's IP") }.joinToString(" or ") { "$it:${b.port}" }
        val who = b.client?.let { "Connected: $it. This desk doesn't follow changes made on the tablet." } ?: "Waiting for a tablet."
        val find = if (b.announce?.startsWith("Announced") == true) "${b.announce}: the tablet lists it (or add bridge $where)." else listOfNotNull(b.announce, "On the tablet, add bridge $where.").joinToString(". ")
        "$find $who"
    }
}

@Composable
private fun MapGrid(map: CcMap, duplicates: Set<Int>, selected: Control.Channel?, cell: Dp, onSelect: (Control.Channel) -> Unit) {
    val cellStyle = TextStyle(color = DeskColors.Ink, fontSize = 12.sp, textAlign = TextAlign.Center)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.width(LABEL_W))
            for (ch in 1..CHANNEL_COUNT) BasicText("CH$ch", style = cellStyle.copy(color = DeskColors.InkDim), modifier = Modifier.width(cell))
        }
        for (param in MAP_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(param.label, style = TextStyle(color = DeskColors.InkDim, fontSize = 11.sp), modifier = Modifier.width(LABEL_W))
                for (ch in 1..CHANNEL_COUNT) {
                    if (!param.availableOn(ch)) {
                        Box(Modifier.width(cell))
                        continue
                    }
                    val control = Control.Channel(ch, param)
                    val cc = map[control]
                    val isSelected = control == selected
                    val shape = RoundedCornerShape(4.dp)
                    Box(
                        Modifier.width(cell).height(34.dp)
                            .background(if (isSelected) Color(0xFF3A2F12) else Color(0xFF15161A), shape)
                            .border(if (isSelected) 2.dp else 1.dp, if (isSelected) DeskColors.Amber else Color(0xFF3A3D42), shape)
                            .clickable { onSelect(control) }
                            .testTag("map-$ch-${param.name}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            cc?.toString() ?: "—",
                            style = cellStyle.copy(color = if (cc != null && cc in duplicates) DeskColors.Red else DeskColors.Ink),
                        )
                    }
                }
            }
        }
    }
}

/** The sound pads' notes, under the CC grid. */
@Composable
private fun PadRow(notes: List<Int?>, selected: Int?, cell: Dp, onSelect: (Int) -> Unit) {
    val duplicates = notes.filterNotNull().groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("PAD NOTE", style = TextStyle(color = DeskColors.InkDim, fontSize = 11.sp), modifier = Modifier.width(LABEL_W))
        notes.forEachIndexed { i, note ->
            val pad = i + 1
            MapCell(
                text = note?.let { "P$pad: $it" } ?: "P$pad: —",
                selected = pad == selected,
                warn = note != null && note in duplicates,
                onClick = { onSelect(pad) },
                modifier = Modifier.width(cell * 2 + 3.dp).testTag("map-pad-$pad"),
            )
        }
    }
}

@Composable
private fun MapCell(text: String, selected: Boolean, warn: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier.height(34.dp)
            .background(if (selected) Color(0xFF3A2F12) else Color(0xFF15161A), shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) DeskColors.Amber else Color(0xFF3A3D42), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TextStyle(color = if (warn) DeskColors.Red else DeskColors.Ink, fontSize = 12.sp, textAlign = TextAlign.Center))
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    BasicText(
        text,
        style = TextStyle(color = if (selected) Color(0xFF111111) else DeskColors.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold),
        modifier = modifier
            .background(if (selected) DeskColors.Amber else DeskColors.ButtonBottom, shape)
            .border(1.dp, Color.Black, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun SmallButton(text: String, enabled: Boolean = true, tag: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    BasicText(
        text,
        style = TextStyle(
            color = if (enabled) DeskColors.Ink else DeskColors.InkDim.copy(alpha = 0.5f),
            fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        ),
        modifier = Modifier
            .heightIn(min = 36.dp)
            .background(DeskColors.ButtonBottom, shape)
            .border(1.dp, Color.Black, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .let { if (tag != null) it.testTag(tag) else it },
    )
}

@Composable
private fun OptionRow(text: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        LampButton(if (on) "ON" else "OFF", on, onChange, DeskColors.Green, Modifier.width(56.dp).height(36.dp).testTag(tag), description = text)
        Hint(text, DeskColors.Ink, Modifier.weight(1f))
    }
}

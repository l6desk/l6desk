package io.github.l6desk.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.model.CHANNEL_COUNT
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.L6maxLayout
import io.github.l6desk.model.MixerState
import io.github.l6desk.model.SceneSlot
import io.github.l6desk.net.RemotePlayer

/** Everything the mixer screen can ask for. */
interface DeskActions : StripActions, MasterActions, SendsMasterActions, SceneNoticeActions {
    /** Opens the bridge's music player panel. */
    fun openPlayer() {}

    /** REPORT: takes a screenshot and opens the problem report dialog. */
    fun openReport() {}

    /** Hides a bridge warning until it changes, or clears and comes back. */
    fun dismissBridgeWarning(warning: BridgeWarning) {}

    override fun pushAll()
    fun openConnections()
    fun toggleMidiLog()
    fun openSetup()
    fun openSends()
    fun backToMix()
}

@Composable
fun MixerScreen(
    state: MixerState,
    connection: ConnectionState,
    needsPush: Boolean,
    actions: DeskActions,
    modifier: Modifier = Modifier,
    midiLogVisible: Boolean = false,
    /** Shown in the top bar while Bridge mode is on (desktop). */
    bridgeLine: String? = null,
    view: DeskView = DeskView.MAIN,
    selectedSend: ChannelParam = ChannelParam.AUX1,
    storedScenes: Set<SceneSlot> = emptySet(),
    currentScene: SceneSlot? = null,
    sceneNotice: SceneNotice? = null,
    /** Sound pads held (in the app or on the mixer), and the profile's pad notes. */
    pads: Set<Int> = emptySet(),
    padNotes: List<Int?> = L6maxLayout.FACTORY_PAD_NOTES,
    /** The connected bridge's music player, or null when there's none. */
    player: RemotePlayer? = null,
    /** Shows REPORT in the top bar. */
    canReport: Boolean = false,
    /** The bridge's warnings to show (under-voltage, heat, space for songs). */
    bridgeWarnings: List<BridgeWarning> = emptyList(),
) {
    Column(modifier.fillMaxSize().background(DeskColors.Background)) {
        BoxWithConstraints {
            // Narrow (portrait): no room for REPORT in the top bar; it's still in SETUP.
            TopBar(connection, needsPush, midiLogVisible, bridgeLine, view, actions, player, canReport && maxWidth >= REPORT_MIN_WIDTH, compact = maxWidth < COMPACT_TOP_BAR)
        }
        if (bridgeWarnings.isNotEmpty()) BridgeWarningsBanner(bridgeWarnings, actions::dismissBridgeWarning)
        if (sceneNotice != null) SceneNoticeBanner(sceneNotice, actions)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val masterWidth = (maxWidth * 0.13f).coerceIn(110.dp, 170.dp)
            val cheek = 12.dp
            val available = maxWidth - masterWidth - cheek * 2
            val metrics = DeskMetrics.forSize(maxWidth, maxHeight, stripArea = available)
            CompositionLocalProvider(LocalDeskMetrics provides metrics) {
                // Landscape: all 8 strips share the width. Too narrow (portrait, or a narrow window):
                // banks of 4, CH 1–4 or CH 5–8, picked with buttons (no swipe: a sideways brush on
                // a stand mustn't switch the bank mid-song). Each bank button shows its channels'
                // mutes and solos, so the hidden bank is still visible at a glance.
                val fits = available / CHANNEL_COUNT >= metrics.stripMinWidth
                var bank by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
                val shown = if (fits) state.channels else state.channels.drop(bank * BANK_SIZE).take(BANK_SIZE)
                // Even 4 don't fit (very narrow): they scroll within the bank.
                val bankFits = fits || available / BANK_SIZE >= metrics.stripMinWidth
                Row(Modifier.fillMaxSize()) {
                    Cheek(cheek)
                    val stripsModifier = if (bankFits) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    }
                    Column(Modifier.width(available).fillMaxHeight()) {
                        if (!fits) BankBar(state, bank, onBank = { bank = it })
                        if (view == DeskView.SENDS) SendBand(selectedSend, actions::selectSend)
                        Row(stripsModifier.weight(1f).testTag("strips")) {
                            for (channel in shown) {
                                ChannelStrip(
                                    channel = channel,
                                    impliedMute = state.impliedMute(channel.number),
                                    actions = actions,
                                    modifier = if (bankFits) Modifier.weight(1f) else Modifier.width(metrics.stripMinWidth),
                                    view = view,
                                    selectedSend = selectedSend,
                                )
                                Box(Modifier.width(1.dp).fillMaxHeight().background(DeskColors.Divider))
                            }
                        }
                    }
                    if (view == DeskView.SENDS) {
                        SendsMasterSection(state, actions, Modifier.width(masterWidth), storedScenes, currentScene)
                    } else {
                        MasterSection(state, actions, Modifier.width(masterWidth), pads, padNotes)
                    }
                    Cheek(cheek)
                }
                if (state.masterMute) MasterMuteFrame()
            }
        }
    }
}

/** Channels per bank when the 8 strips don't fit side by side (portrait). */
private const val BANK_SIZE = 4

/**
 * CH 1–4 / CH 5–8 (portrait): picks the bank of strips shown. Each button has a small lamp per
 * channel: red while it's muted (its own mute, a solo or INSTANT MUTE), amber while it's soloed.
 */
@Composable
private fun BankBar(state: MixerState, bank: Int, onBank: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp).testTag("bank-bar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (b in 0 until CHANNEL_COUNT / BANK_SIZE) {
            val channels = state.channels.drop(b * BANK_SIZE).take(BANK_SIZE)
            val on = b == bank
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp)
            Row(
                Modifier.weight(1f).height(48.dp)
                    .background(if (on) DeskColors.Amber.copy(alpha = 0.22f) else DeskColors.ButtonBottom, shape)
                    .border(if (on) 2.dp else 1.dp, if (on) DeskColors.Amber else Color.Black, shape)
                    .clickable { onBank(b) }
                    .padding(horizontal = 10.dp)
                    .testTag("bank-$b"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText(
                    "CH ${channels.first().number}–${channels.last().number}",
                    style = TextStyle(color = if (on) DeskColors.Ink else DeskColors.InkDim, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                    modifier = Modifier.weight(1f),
                )
                for (c in channels) {
                    val muted = state.effectiveMute(c.number)
                    Box(
                        Modifier.size(10.dp)
                            .background(if (muted) DeskColors.Red else Color(0xFF2A2C30), androidx.compose.foundation.shape.CircleShape)
                            .border(if (c.solo) 2.dp else 1.dp, if (c.solo) DeskColors.Amber else Color.Black, androidx.compose.foundation.shape.CircleShape)
                            .testTag("bank-lamp-${c.number}"),
                    )
                }
            }
        }
    }
}

/** The top bar shows REPORT only at least this wide. */
private val REPORT_MIN_WIDTH = 900.dp

/**
 * Narrower than this (portrait), the top bar drops the app's name and the MIDI button (the MIDI
 * log is in SETUP → OPTIONS too) and tightens up, so SENDS, PLAYER, SETUP and SEND DESK all fit.
 */
private val COMPACT_TOP_BAR = 760.dp

@Composable
private fun TopBar(
    connection: ConnectionState,
    needsPush: Boolean,
    midiLogVisible: Boolean,
    bridgeLine: String?,
    view: DeskView,
    actions: DeskActions,
    player: RemotePlayer?,
    canReport: Boolean,
    compact: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).background(DeskColors.Bridge).padding(horizontal = if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
    ) {
        if (!compact) {
            BasicText(
                io.github.l6desk.AppInfo.NAME,
                style = TextStyle(color = DeskColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp),
            )
        }
        val (lampColor, text) = when (connection) {
            is ConnectionState.Connected -> DeskColors.Green to connection.device.name
            is ConnectionState.Connecting -> DeskColors.Amber to "Connecting to ${connection.device.name}…"
            is ConnectionState.Error -> DeskColors.Red to "Error: ${connection.reason}"
            ConnectionState.Disconnected -> Color(0xFF5A2420) to "Not connected — tap to choose"
        }
        Row(
            Modifier.weight(1f).fillMaxHeight().clickable(onClick = actions::openConnections).testTag("connection-button"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(9.dp).background(lampColor, CircleShape))
            BasicText(
                "  $text",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = DeskColors.InkDim, fontSize = 11.sp),
                modifier = Modifier.testTag("connection-status"),
            )
            if (bridgeLine != null) {
                BasicText(
                    "   ·   $bridgeLine",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = DeskColors.Amber, fontSize = 11.sp),
                    modifier = Modifier.testTag("bridge-line"),
                )
            }
        }
        if (player != null) NowPlaying(player, actions::openPlayer, if (compact) Modifier.widthIn(max = 140.dp) else Modifier)
        if (view == DeskView.SENDS) {
            BasicText("SENDS VIEW", style = TextStyle(color = DeskColors.SendEfx, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp))
            LampButton(
                "BACK TO MIX", true, { actions.backToMix() }, DeskColors.Amber,
                Modifier.width(140.dp).height(32.dp).testTag("back-to-mix"), description = "Back to the main mix", fontSize = 12.sp,
            )
        } else {
            LampButton(
                "SENDS", false, { actions.openSends() }, DeskColors.SendEfx,
                Modifier.width(76.dp).height(30.dp).testTag("sends-button"), description = "Sends view",
            )
        }
        // PLAYER after SENDS: in a set, SENDS is reached for more often.
        if (player != null) {
            LampButton(
                "PLAYER", player.state.playing != null, { actions.openPlayer() }, DeskColors.Pad,
                Modifier.width(80.dp).height(30.dp).testTag("player-button"), description = "Music player",
            )
        }
        if (!compact) {
            LampButton(
                "MIDI", midiLogVisible, { actions.toggleMidiLog() }, DeskColors.Green,
                Modifier.width(56.dp).height(30.dp).testTag("midi-log-toggle"), description = "Show MIDI log",
            )
        }
        if (canReport) {
            LampButton(
                "REPORT", false, { actions.openReport() }, DeskColors.Ink,
                Modifier.width(72.dp).height(30.dp).testTag("report-button"), description = "Report a problem",
            )
        }
        LampButton(
            "SETUP", false, { actions.openSetup() }, DeskColors.Ink,
            Modifier.width(70.dp).height(30.dp).testTag("setup-button"), description = "Setup",
        )
        PushButton(enabled = connection is ConnectionState.Connected, attention = needsPush, onClick = actions::pushAll)
    }
}

@Composable
private fun PushButton(enabled: Boolean, attention: Boolean, onClick: () -> Unit) {
    // The pulse is read only while drawing (graphicsLayer), so it redraws the button alone, not
    // the whole top bar every frame: it runs for as long as the desk hasn't been sent.
    val pulse: androidx.compose.runtime.State<Float> = if (attention) {
        rememberInfiniteTransition().animateFloat(1f, 0.6f, infiniteRepeatable(tween(700), RepeatMode.Reverse))
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    }
    LampButton(
        label = "SEND DESK",
        on = attention,
        onToggle = { if (enabled) onClick() },
        lamp = DeskColors.Amber,
        description = if (attention) "Send desk to mixer (mixer may not match)" else "Send desk to mixer",
        modifier = Modifier.width(110.dp).height(30.dp).graphicsLayer { alpha = if (enabled) pulse.value else 0.4f }.testTag("send-desk"),
    )
}

@Composable
private fun Cheek(width: androidx.compose.ui.unit.Dp) = Box(
    Modifier.width(width).fillMaxHeight().background(
        Brush.horizontalGradient(listOf(DeskColors.WoodDark, DeskColors.Wood, DeskColors.WoodDark)),
    ),
)

/** A flashing red frame around the desk while instant mute is on: impossible to miss. */
@Composable
private fun MasterMuteFrame() {
    val a = rememberInfiniteTransition().animateFloat(1f, 0.25f, infiniteRepeatable(tween(400), RepeatMode.Reverse))
    // Read while drawing only, so only the frame redraws.
    Box(
        Modifier.fillMaxSize().drawBehind {
            val w = 4.dp.toPx()
            drawRect(DeskColors.Red.copy(alpha = a.value), topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        }.testTag("master-mute-frame"),
    )
}

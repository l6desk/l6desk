package io.github.l6desk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.l6desk.model.SceneSlot
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.ChannelState
import io.github.l6desk.model.EfxType
import io.github.l6desk.model.GlobalParam
import io.github.l6desk.model.MixerState
import io.github.l6desk.model.SEND_PARAMS
import io.github.l6desk.model.isOn
import io.github.l6desk.model.toggleValue

/** MAIN: the desk as mixed live. SENDS: the faders set the selected send for each channel. */
enum class DeskView { MAIN, SENDS }

fun sendColor(param: ChannelParam): Color = when (param) {
    ChannelParam.AUX1 -> DeskColors.SendAux1
    ChannelParam.AUX2 -> DeskColors.SendAux2
    ChannelParam.SUB_MIX -> DeskColors.SendSubMix
    else -> DeskColors.SendEfx
}

fun sendLabel(param: ChannelParam): String = when (param) {
    ChannelParam.AUX1 -> "AUX 1"
    ChannelParam.AUX2 -> "AUX 2"
    ChannelParam.SUB_MIX -> "SUB MIX"
    else -> "EFX"
}

/** The band of send toggles across the top of the strips in the SENDS view: exactly one is lit. */
@Composable
fun SendBand(selected: ChannelParam, onSelect: (ChannelParam) -> Unit, modifier: Modifier = Modifier) {
    val m = LocalDeskMetrics.current
    Row(
        modifier.fillMaxWidth().background(DeskColors.Panel).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (p in SEND_PARAMS) {
            LampButton(
                sendLabel(p), p == selected, { onSelect(p) }, sendColor(p),
                Modifier.weight(1f).height(m.lamp).testTag("send-${p.name}"),
                description = "Faders set ${sendLabel(p)}", fontSize = 13.sp,
            )
        }
    }
}

/**
 * Replaces the EQ and pan knobs in the SENDS view: all four of the channel's send levels as bars
 * ("—" while unknown). The selected send is lit; tapping a row selects that send for every fader.
 */
@Composable
fun SendOverview(channel: ChannelState, selected: ChannelParam, onSelect: (ChannelParam) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        SectionLabel("SENDS")
        for (p in SEND_PARAMS) {
            val value = channel.send(p)
            val lit = p == selected
            val color = sendColor(p)
            val shape = RoundedCornerShape(4.dp)
            Column(
                Modifier.fillMaxWidth()
                    .background(if (lit) color.copy(alpha = 0.16f) else Color.Transparent, shape)
                    .border(1.dp, if (lit) color else Color.Transparent, shape)
                    .clickable { onSelect(p) }
                    .padding(horizontal = 4.dp, vertical = 3.dp)
                    .testTag("ch${channel.number}-overview-${p.name}"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(sendLabel(p), style = TextStyle(color = if (lit) color else DeskColors.InkDim, fontSize = 9.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    BasicText(value?.toString() ?: "—", style = TextStyle(color = DeskColors.Ink, fontSize = 9.sp, textAlign = TextAlign.End))
                }
                Box(Modifier.fillMaxWidth().height(6.dp).background(DeskColors.Slot, RoundedCornerShape(3.dp))) {
                    if (value != null && value > 0) {
                        Box(Modifier.fillMaxWidth(value / 127f).fillMaxHeight().background(color, RoundedCornerShape(3.dp)))
                    }
                }
            }
        }
    }
}

interface SendsMasterActions {
    fun setMasterMute(on: Boolean)
    fun setGlobal(param: GlobalParam, value: Int)

    /** Stores the current desk in an app scene. */
    fun storeScene(slot: SceneSlot)

    /** Recalls an app scene (already confirmed by the second tap). */
    fun recallScene(slot: SceneSlot)
}

/** The master section in the SENDS view: instant mute (always to hand), EFX TYPE and COMP. No master fader. */
@Composable
fun SendsMasterSection(
    state: MixerState,
    actions: SendsMasterActions,
    modifier: Modifier = Modifier,
    storedScenes: Set<SceneSlot> = emptySet(),
    currentScene: SceneSlot? = null,
) {
    val m = LocalDeskMetrics.current
    Column(
        modifier.fillMaxHeight().background(DeskColors.PanelMaster).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText("SENDS", style = TextStyle(color = DeskColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold))
        Rule()
        LampButton(
            "INSTANT\nMUTE", state.masterMute, actions::setMasterMute, DeskColors.Red,
            Modifier.fillMaxWidth().height(m.lamp * 2).testTag("master-mute"),
            description = "Instant mute all channels", fontSize = 12.sp,
        )
        Box(Modifier.height(4.dp))
        SectionLabel("EFX TYPE")
        val current = state.globals[GlobalParam.EFX_TYPE]?.let(EfxType::of)
        for (row in EfxType.entries.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in row) {
                    LampButton(
                        t.label, t == current, { actions.setGlobal(GlobalParam.EFX_TYPE, t.value) }, DeskColors.SendEfx,
                        Modifier.weight(1f).height(m.lamp * 0.8f).testTag("efx-${t.name}"),
                        description = "Effect ${t.label}", fontSize = 9.sp,
                    )
                }
            }
        }
        if (current == null) BasicText("type not known yet", style = TextStyle(color = DeskColors.InkDim, fontSize = 8.sp))
        Box(Modifier.height(4.dp))
        val comp = state.globals[GlobalParam.COMPRESSOR]
        LampButton(
            if (comp == null) "COMP ?" else "COMP", comp != null && isOn(comp),
            { on -> actions.setGlobal(GlobalParam.COMPRESSOR, toggleValue(on)) }, DeskColors.Amber,
            Modifier.fillMaxWidth().height(m.lamp * 0.9f).testTag("comp"),
            description = "Master compressor", fontSize = 11.sp,
        )
        Box(Modifier.height(4.dp))
        Scenes(storedScenes, currentScene, actions)
        Box(Modifier.weight(1f))
    }
}

/**
 * App scenes A–D. Hold a button to store the desk in it. Tap a stored scene once to arm it (it
 * flashes), then again within 3 s to recall: one stray tap can't swap the whole mix mid-song.
 */
@Composable
private fun Scenes(stored: Set<SceneSlot>, current: SceneSlot?, actions: SendsMasterActions) {
    var armed by remember { mutableStateOf<SceneSlot?>(null) }
    var message by remember { mutableStateOf("Hold to store · tap twice to recall") }
    LaunchedEffect(armed) {
        if (armed != null) {
            delay(3_000)
            armed = null
            message = "Hold to store · tap twice to recall"
        }
    }
    SectionLabel("SCENES")
    for (row in SceneSlot.entries.chunked(2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (slot in row) {
                SceneButton(
                    slot = slot,
                    stored = slot in stored,
                    current = slot == current,
                    armed = slot == armed,
                    onTap = {
                        when {
                            slot !in stored -> message = "Scene ${slot.name} is empty: hold to store"
                            armed == slot -> {
                                armed = null
                                actions.recallScene(slot)
                                message = "Scene ${slot.name} recalled"
                            }
                            else -> {
                                armed = slot
                                message = "Tap ${slot.name} again to recall"
                            }
                        }
                    },
                    onHold = {
                        armed = null
                        actions.storeScene(slot)
                        message = "Desk stored in scene ${slot.name}"
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    BasicText(
        message,
        style = TextStyle(color = DeskColors.InkDim, fontSize = 8.sp, textAlign = TextAlign.Center),
        modifier = Modifier.fillMaxWidth().testTag("scene-message"),
    )
}

@Composable
private fun SceneButton(
    slot: SceneSlot,
    stored: Boolean,
    current: Boolean,
    armed: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = LocalDeskMetrics.current
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val flash = if (armed) {
        val t = rememberInfiniteTransition()
        val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(250), RepeatMode.Reverse))
        a
    } else {
        1f
    }
    val shape = RoundedCornerShape(5.dp)
    val bg = when {
        armed -> DeskColors.Amber.copy(alpha = flash)
        current -> DeskColors.Green
        else -> DeskColors.ButtonBottom
    }
    val ink = when {
        armed || current -> Color(0xFF111111)
        stored -> DeskColors.Ink
        else -> DeskColors.InkDim.copy(alpha = 0.5f)
    }
    Box(
        modifier
            .height(m.lamp * 0.85f)
            .background(bg, shape)
            .border(1.dp, if (stored) Color.Black else DeskColors.ButtonTop, shape)
            .semantics {
                role = Role.Button
                contentDescription = "Scene ${slot.name}"
                stateDescription = when {
                    armed -> "armed"
                    current -> "current"
                    stored -> "stored"
                    else -> "empty"
                }
                onClick { tap(); true }
                onLongClick { hold(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    val up = withTimeoutOrNull(700) { waitForUpOrCancellation() }
                    if (up == null) {
                        hold()
                        waitForUpOrCancellation()
                    } else {
                        tap()
                    }
                }
            }
            .testTag("scene-${slot.name}"),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(slot.name, style = TextStyle(color = ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold))
    }
}

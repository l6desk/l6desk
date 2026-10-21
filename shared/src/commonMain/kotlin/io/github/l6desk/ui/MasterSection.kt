package io.github.l6desk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.l6desk.model.L6maxLayout
import io.github.l6desk.model.MixerState

interface MasterActions {
    fun setMaster(value: Int)
    fun setMasterMute(on: Boolean)
    fun clearSolo()

    /** Sound pad [pad] (1–4) pressed ([down]) or released. */
    fun setPad(pad: Int, down: Boolean)
}

/** App-side master: a VCA over all channel levels, instant mute, solo status and the sound pads (CLAUDE.md). */
@Composable
fun MasterSection(
    state: MixerState,
    actions: MasterActions,
    modifier: Modifier = Modifier,
    pads: Set<Int> = emptySet(),
    padNotes: List<Int?> = L6maxLayout.FACTORY_PAD_NOTES,
) {
    val m = LocalDeskMetrics.current
    Column(
        modifier.fillMaxHeight().background(DeskColors.PanelMaster).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText("MASTER", style = TextStyle(color = DeskColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold))
        Rule()
        LampButton(
            "INSTANT\nMUTE", state.masterMute, actions::setMasterMute, DeskColors.Red,
            Modifier.fillMaxWidth().height(m.lamp * 2).testTag("master-mute"),
            description = "Instant mute all channels", fontSize = 12.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val lit = state.anySolo
            Box(
                Modifier.size(8.dp)
                    .shadow(if (lit) 6.dp else 0.dp, CircleShape, spotColor = DeskColors.Amber)
                    .background(if (lit) DeskColors.Amber else DeskColors.ButtonBottom, CircleShape),
            )
            BasicText(
                "  SOLO ACTIVE",
                style = TextStyle(color = if (lit) DeskColors.Amber else DeskColors.InkDim, fontSize = 9.sp),
            )
        }
        LampButton(
            "CLEAR SOLO", false, { actions.clearSolo() }, DeskColors.Amber,
            Modifier.fillMaxWidth().height(m.lamp * 0.8f).testTag("clear-solo"), fontSize = 9.sp,
        )
        SoundPads(pads, padNotes, actions::setPad, masterMute = state.masterMute)
        Fader(
            value = state.master,
            onValueChange = actions::setMaster,
            label = "Master level",
            default = MixerState.MASTER_UNITY,
            unityMark = MixerState.MASTER_UNITY,
            style = FaderStyle.MASTER,
            capHeight = m.faderCap,
            modifier = Modifier.weight(1f).testTag("master-level"),
        )
        Scribble("MAIN L/R")
    }
}

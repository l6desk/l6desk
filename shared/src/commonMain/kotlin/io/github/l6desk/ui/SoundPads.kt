package io.github.l6desk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.l6desk.model.L6maxLayout

/**
 * The L6max's four SOUND PAD buttons, 2×2. [lit] are the pads held (here or on the mixer);
 * [padNotes] greys out pads the profile doesn't map. The pads bypass the channels, so instant
 * mute can't silence them: while it's on ([masterMute]) the label says so.
 */
@Composable
fun SoundPads(
    lit: Set<Int>,
    padNotes: List<Int?>,
    onPress: (pad: Int, down: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    masterMute: Boolean = false,
) {
    val height = maxOf(LocalDeskMetrics.current.lamp, 48.dp)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            if (masterMute) "PADS NOT MUTED" else "SOUND PADS",
            style = TextStyle(
                color = if (masterMute) DeskColors.Red else DeskColors.InkDim,
                fontSize = 9.sp, letterSpacing = 1.5.sp,
                fontWeight = if (masterMute) FontWeight.ExtraBold else FontWeight.Normal,
            ),
            modifier = Modifier.testTag("pads-label"),
        )
        for (row in (1..L6maxLayout.PAD_COUNT).chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (pad in row) {
                    PadButton(
                        pad, pad in lit, enabled = padNotes.getOrNull(pad - 1) != null, onPress = onPress,
                        Modifier.weight(1f).height(height).testTag("pad-$pad"),
                    )
                }
            }
        }
    }
}

/**
 * A momentary pad: pressed while the finger is down, like the hardware pad, so the mixer can
 * apply the pad's play mode (Hold plays only while held; holding a One-shot pad stops it).
 */
@Composable
private fun PadButton(pad: Int, lit: Boolean, enabled: Boolean, onPress: (Int, Boolean) -> Unit, modifier: Modifier) {
    val press by rememberUpdatedState(onPress)
    var held by remember { mutableStateOf(false) }
    // Never leave a note hanging if the pad leaves the screen mid-press (e.g. switching view).
    DisposableEffect(Unit) { onDispose { if (held) press(pad, false) } }
    val shape = RoundedCornerShape(6.dp)
    val glow = lit && enabled
    Box(
        modifier
            .shadow(if (glow) 10.dp else 2.dp, shape, ambientColor = DeskColors.Pad, spotColor = if (glow) DeskColors.Pad else Color.Black)
            .background(
                when {
                    glow -> Brush.radialGradient(listOf(lerp(DeskColors.Pad, Color.White, 0.4f), DeskColors.Pad))
                    else -> Brush.verticalGradient(listOf(DeskColors.ButtonTop, DeskColors.ButtonBottom))
                },
                shape,
            )
            .border(1.dp, if (enabled) lerp(DeskColors.Pad, Color.Black, 0.55f) else Color.Black, shape)
            .semantics {
                role = Role.Button
                contentDescription = "Sound pad $pad"
                if (!enabled) disabled()
                onClick { press(pad, true); press(pad, false); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown().consume()
                    held = true
                    press(pad, true)
                    try {
                        waitForUpOrCancellation()?.consume()
                    } finally {
                        held = false
                        press(pad, false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            "$pad",
            style = TextStyle(
                color = when {
                    glow -> Color(0xFF0B1A1C)
                    enabled -> DeskColors.Pad
                    else -> DeskColors.InkDim.copy(alpha = 0.4f)
                },
                fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
            ),
        )
    }
}

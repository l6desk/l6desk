package io.github.l6desk.ui

import androidx.compose.foundation.layout.padding

import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Latching illuminated button (mute, solo, instant mute). It toggles the moment it is pressed,
 * not on release, for instant response. [implied] lights it dimly when the state is forced
 * by something else (a channel muted by solo or master mute).
 */
@Composable
fun LampButton(
    label: String,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    lamp: Color,
    modifier: Modifier = Modifier,
    implied: Boolean = false,
    description: String = label,
    fontSize: TextUnit = 10.sp,
    /** For labels that may not fit (e.g. playlist names): cut short with "…" after this many lines. */
    maxLines: Int = Int.MAX_VALUE,
) {
    val currentOn by rememberUpdatedState(on)
    val currentToggle by rememberUpdatedState(onToggle)
    val shape = RoundedCornerShape(4.dp)
    val background = when {
        on -> Brush.radialGradient(listOf(lerp(lamp, Color.White, 0.35f), lamp))
        implied -> Brush.verticalGradient(listOf(lerp(lamp, Color.Black, 0.65f), lerp(lamp, Color.Black, 0.78f)))
        else -> Brush.verticalGradient(listOf(DeskColors.ButtonTop, DeskColors.ButtonBottom))
    }
    val textColor = when {
        on -> Color(0xFF1A0F0B)
        implied -> lamp
        else -> lerp(lamp, DeskColors.ButtonBottom, 0.45f)
    }
    Box(
        modifier
            .shadow(if (on) 8.dp else 2.dp, shape, ambientColor = lamp, spotColor = if (on) lamp else Color.Black)
            .background(background, shape)
            .border(1.dp, if (implied) lamp.copy(alpha = 0.5f) else Color.Black, shape)
            .semantics {
                role = Role.Switch
                contentDescription = description
                stateDescription = when {
                    on -> "On"
                    implied -> "Off (forced on)"
                    else -> "Off"
                }
                onClick { currentToggle(!currentOn); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    currentToggle(!currentOn)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = TextStyle(color = textColor, fontSize = fontSize, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, letterSpacing = 0.8.sp),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = if (maxLines == Int.MAX_VALUE) Modifier else Modifier.padding(horizontal = 6.dp),
        )
    }
}

internal fun lerp(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)

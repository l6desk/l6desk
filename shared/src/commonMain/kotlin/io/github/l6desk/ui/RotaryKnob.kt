package io.github.l6desk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SWEEP = 300f
private const val START = -150f // degrees clockwise from 12 o'clock

/**
 * Rotary knob with a 300° sweep. Drag vertically to turn it (not a circular gesture); double-tap
 * to return it to [default]. Arrow keys adjust it when focused (desktop).
 */
@Composable
fun RotaryKnob(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    default: Int = 64,
    bipolar: Boolean = true,
    size: Dp = 48.dp,
    describe: (Int) -> String = { it.toString() },
    onTouchChange: (Boolean) -> Unit = {},
) {
    var active by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .semantics {
                    contentDescription = label
                    stateDescription = describe(value)
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..127f)
                    setProgress { target -> onValueChange(target.roundToInt().coerceIn(0, 127)); true }
                }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val step = when (e.key) {
                        Key.DirectionUp, Key.DirectionRight -> 1
                        Key.DirectionDown, Key.DirectionLeft -> -1
                        Key.PageUp -> 8
                        Key.PageDown -> -8
                        else -> return@onKeyEvent false
                    }
                    onValueChange((value + step).coerceIn(0, 127))
                    true
                }
                .focusable()
                .valueDrag(
                    value = value,
                    onValueChange = onValueChange,
                    pxPerStep = { 1.6f * density },
                    onReset = { onValueChange(default) },
                    onActiveChange = { active = it; onTouchChange(it) },
                ),
        ) {
            Canvas(Modifier.size(size)) { drawKnob(value, color, bipolar, active) }
        }
        BasicText(
            label,
            style = TextStyle(color = DeskColors.Ink, fontSize = 9.sp, textAlign = TextAlign.Center, letterSpacing = 1.sp),
        )
    }
}

private fun DrawScope.drawKnob(value: Int, color: Color, bipolar: Boolean, active: Boolean) {
    val r = size.minDimension / 2
    val c = center
    fun polar(deg: Float, radius: Float): Offset {
        val a = deg * (kotlin.math.PI.toFloat() / 180f)
        return Offset(c.x + radius * sin(a), c.y - radius * cos(a))
    }

    // Printed scale: 11 ticks, longer at the ends and centre.
    for (t in 0..10) {
        val a = START + t * 30f
        val inner = if (t == 0 || t == 5 || t == 10) r * 0.80f else r * 0.86f
        drawLine(DeskColors.Tick, polar(a, r * 0.94f), polar(a, inner), strokeWidth = r * 0.05f, cap = StrokeCap.Round)
    }

    // Value arc, from the centre for bipolar controls (pan, EQ gain) or from the start otherwise.
    val angle = START + value / 127f * SWEEP
    val from = if (bipolar) 0f else START
    val (a0, a1) = if (angle < from) angle to from else from to angle
    if (a1 - a0 > 0.5f) {
        val ar = r * 0.76f
        drawArc(
            color = color.copy(alpha = 0.85f),
            startAngle = a0 - 90f,
            sweepAngle = a1 - a0,
            useCenter = false,
            topLeft = Offset(c.x - ar, c.y - ar),
            size = Size(ar * 2, ar * 2),
            style = Stroke(width = r * 0.07f, cap = StrokeCap.Round),
        )
    }

    // Cap: shadow, skirt, coloured top and pointer line.
    drawCircle(Color.Black.copy(alpha = 0.45f), radius = r * 0.66f, center = c + Offset(0f, r * 0.05f))
    drawCircle(DeskColors.Skirt, radius = r * 0.64f, center = c)
    drawCircle(if (active) lerpBrighter(color) else color, radius = r * 0.47f, center = c)
    val pointer = if (color == DeskColors.KnobGrey) Color(0xFF222222) else Color.White
    rotate(angle, pivot = c) {
        drawLine(pointer, Offset(c.x, c.y - r * 0.60f), Offset(c.x, c.y - r * 0.26f), strokeWidth = r * 0.09f, cap = StrokeCap.Round)
    }
}

private fun lerpBrighter(c: Color) = Color(
    red = (c.red + (1 - c.red) * 0.2f),
    green = (c.green + (1 - c.green) * 0.2f),
    blue = (c.blue + (1 - c.blue) * 0.2f),
    alpha = c.alpha,
)

package io.github.l6desk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

enum class FaderStyle { CHANNEL, MASTER }

/**
 * Long-throw vertical fader with a 0–10 scale. Dragging moves the cap relative to the finger,
 * so touching the fader never makes it jump. Double-tap the cap to return to [default].
 */
@Composable
fun Fader(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    default: Int = 100,
    style: FaderStyle = FaderStyle.CHANNEL,
    capHeight: Dp = 36.dp,
    onTouchChange: (Boolean) -> Unit = {},
    /** Value to mark on the slot in amber (the master's unity point), or null. */
    unityMark: Int? = null,
    /** Cap colour instead of the style's (a send's colour in the SENDS view). */
    capColor: Color? = null,
    /** The value isn't known (a send not yet set or reported): grey parked cap, "unknown" state. */
    unknown: Boolean = false,
) {
    var active by remember { mutableStateOf(false) }
    var slotSize by remember { mutableStateOf(IntSize.Zero) }
    val capPx = with(LocalDensity.current) { capHeight.toPx() }
    Row(modifier.padding(vertical = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        // Scale: 10 at the top, 0 at the bottom, centred on the cap travel.
        Column(
            Modifier.fillMaxHeight().padding(vertical = capHeight / 2 - 5.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            for (n in 10 downTo 0) {
                BasicText("$n —", style = TextStyle(color = DeskColors.InkDim, fontSize = 8.sp, lineHeight = 10.sp))
            }
        }
        BoxWithConstraints(
            Modifier
                .fillMaxHeight()
                .width(44.dp)
                .onSizeChanged { slotSize = it }
                .semantics {
                    contentDescription = label
                    stateDescription = if (unknown) "unknown" else value.toString()
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..127f)
                    setProgress { target -> onValueChange(target.roundToInt().coerceIn(0, 127)); true }
                }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val step = when (e.key) {
                        Key.DirectionUp -> 1
                        Key.DirectionDown -> -1
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
                    pxPerStep = { ((slotSize.height - capPx) / 127f).coerceAtLeast(0.5f) },
                    onReset = { onValueChange(default) },
                    resetArea = { pos ->
                        val capTop = (1f - value / 127f) * (slotSize.height - capPx)
                        pos.y in capTop..(capTop + capPx)
                    },
                    onActiveChange = { active = it; onTouchChange(it) },
                ),
        ) {
            Canvas(Modifier.matchParentSize()) { drawFader(value, capHeight.toPx(), style, active, unityMark, capColor, unknown) }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFader(
    value: Int,
    capPx: Float,
    style: FaderStyle,
    active: Boolean,
    unityMark: Int?,
    capColor: Color?,
    unknown: Boolean,
) {
    val w = size.width
    val travel = size.height - capPx
    // Unity mark: an amber line across the slot, behind the cap.
    if (unityMark != null) {
        val y = (1f - unityMark / 127f) * travel + capPx / 2
        drawRect(DeskColors.Amber, topLeft = Offset(0f, y - 1.dp.toPx()), size = Size(w, 2.dp.toPx()))
    }
    // Slot
    val slotW = 5.dp.toPx()
    drawRoundRect(
        DeskColors.Slot,
        topLeft = Offset((w - slotW) / 2, capPx / 2),
        size = Size(slotW, travel),
        cornerRadius = CornerRadius(slotW / 2),
    )
    // Cap
    val top = (1f - value / 127f) * travel
    val brush = when {
        unknown -> Brush.verticalGradient(listOf(Color(0xFF4A4C50), Color(0xFF34363A)), startY = top, endY = top + capPx)
        capColor != null -> Brush.verticalGradient(
            0f to lerp(capColor, Color.White, 0.25f), 0.48f to capColor, 0.52f to lerp(capColor, Color.Black, 0.35f), 1f to capColor,
            startY = top, endY = top + capPx,
        )
        style == FaderStyle.CHANNEL -> Brush.verticalGradient(
            0f to Color(0xFF5A5D63), 0.48f to Color(0xFF2A2C30), 0.52f to Color(0xFF1A1B1D), 1f to Color(0xFF3F4247),
            startY = top, endY = top + capPx,
        )
        else -> Brush.verticalGradient(
            0f to Color(0xFFE0574D), 0.48f to Color(0xFFA4261E), 0.52f to Color(0xFF7D1B15), 1f to Color(0xFFC23A30),
            startY = top, endY = top + capPx,
        )
    }
    drawRoundRect(Color.Black.copy(alpha = 0.6f), topLeft = Offset(0f, top + 4.dp.toPx()), size = Size(w, capPx), cornerRadius = CornerRadius(3.dp.toPx()))
    drawRoundRect(brush, topLeft = Offset(0f, top), size = Size(w, capPx), cornerRadius = CornerRadius(3.dp.toPx()), alpha = if (active) 1f else 0.95f)
    // Grip lines and the white index line
    val lineH = 1.dp.toPx()
    var y = top + 3.dp.toPx()
    while (y < top + capPx - 3.dp.toPx()) {
        drawRect(Color.White.copy(alpha = 0.08f), topLeft = Offset(0f, y), size = Size(w, lineH))
        y += 4.dp.toPx()
    }
    if (!unknown) drawRect(Color(0xFFF0EDE6), topLeft = Offset(w * 0.08f, top + capPx / 2 - lineH), size = Size(w * 0.84f, lineH * 2))
}

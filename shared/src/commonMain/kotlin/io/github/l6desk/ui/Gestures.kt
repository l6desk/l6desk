package io.github.l6desk.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

private class TapMemory {
    var lastTapUptime = -1L
}

/**
 * Relative vertical drag for knobs and faders: dragging up raises the value, starting from
 * wherever it was (no jumps when the control is touched, which matters when mixing live).
 * Movement inside the touch slop is ignored so a tap doesn't nudge the value. Shift (desktop)
 * makes the drag four times finer. A double tap inside [resetArea] calls [onReset].
 *
 * Each pointer is tracked on its own, so several controls can move at once on a touchscreen.
 */
@Composable
fun Modifier.valueDrag(
    value: Int,
    onValueChange: (Int) -> Unit,
    pxPerStep: () -> Float,
    onReset: () -> Unit,
    resetArea: (Offset) -> Boolean = { true },
    onActiveChange: (Boolean) -> Unit = {},
): Modifier {
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnReset by rememberUpdatedState(onReset)
    val currentResetArea by rememberUpdatedState(resetArea)
    val currentOnActive by rememberUpdatedState(onActiveChange)
    val taps = remember { TapMemory() }

    return pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            currentOnActive(true)
            val startValue = currentValue
            var travel = 0f
            var dragging = false
            drag(down.id) { change ->
                val delta = change.position - change.previousPosition
                val fine = if (currentEvent.keyboardModifiers.isShiftPressed) 4f else 1f
                travel += (-delta.y + delta.x * 0.5f) / fine
                if (!dragging && abs(travel) > slop) dragging = true
                if (dragging) {
                    val effective = travel - sign(travel) * slop
                    val next = (startValue + effective / pxPerStep()).roundToInt().coerceIn(0, 127)
                    if (next != currentValue) currentOnChange(next)
                }
                change.consume()
            }
            currentOnActive(false)
            if (!dragging) {
                val now = down.uptimeMillis
                val isDouble = taps.lastTapUptime >= 0 && now - taps.lastTapUptime < viewConfiguration.doubleTapTimeoutMillis
                if (isDouble && currentResetArea(down.position)) {
                    taps.lastTapUptime = -1
                    currentOnReset()
                } else {
                    taps.lastTapUptime = now
                }
            }
        }
    }
}

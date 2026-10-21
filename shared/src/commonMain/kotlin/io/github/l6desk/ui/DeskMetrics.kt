package io.github.l6desk.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Control sizes, scaled to the screen (the Galaxy Tab A11 is ~1005×600 dp in landscape).
 * Knobs follow the height, but are also capped by the width so that in portrait at least
 * four strips are visible before scrolling.
 */
data class DeskMetrics(val knob: Dp, val lamp: Dp, val faderCap: Dp, val stripMinWidth: Dp) {
    companion object {
        fun forSize(width: Dp, height: Dp, stripArea: Dp = width): DeskMetrics {
            val byWidth = (stripArea / 4 - 12.dp) / 2
            val knob = minOf(height * 0.08f, byWidth).coerceIn(44.dp, 64.dp)
            return DeskMetrics(
                knob = knob,
                lamp = (height * 0.075f).coerceIn(40.dp, 52.dp),
                faderCap = (height * 0.06f).coerceIn(32.dp, 44.dp),
                stripMinWidth = knob * 2 + 12.dp,
            )
        }
    }
}

val LocalDeskMetrics = staticCompositionLocalOf { DeskMetrics.forSize(1005.dp, 600.dp) }

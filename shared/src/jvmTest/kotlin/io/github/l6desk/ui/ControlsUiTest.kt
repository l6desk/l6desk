package io.github.l6desk.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ControlsUiTest {
    @Test
    fun knobDragUpRaisesItAndDragDownLowersIt() = runDesktopComposeUiTest(300, 300) {
        var v by mutableStateOf(64)
        setContent { RotaryKnob(v, { v = it }, "PAN", DeskColors.KnobGrey, Modifier.testTag("knob")) }
        onNodeWithContentDescription("PAN").performTouchInput { swipe(center, center - Offset(0f, 60f), 200) }
        assertTrue(v > 64, "dragging up raised it to $v")
        val raised = v
        onNodeWithContentDescription("PAN").performTouchInput { swipe(center, center + Offset(0f, 120f), 200) }
        assertTrue(v < raised, "dragging down lowered it to $v")
    }

    @Test
    fun knobDoesNotJumpWhenTouchedAndDoubleTapResets() = runDesktopComposeUiTest(300, 300) {
        var v by mutableStateOf(20)
        setContent { RotaryKnob(v, { v = it }, "HI", DeskColors.KnobRed, default = 64) }
        onNodeWithContentDescription("HI").performClick()
        assertEquals(20, v, "a single tap changes nothing")
        onNodeWithContentDescription("HI").performTouchInput { doubleClick() }
        assertEquals(64, v)
    }

    @Test
    fun faderDragsRelativeAndDoubleTapOnTheCapResets() = runDesktopComposeUiTest(200, 400) {
        var v by mutableStateOf(30)
        setContent { Fader(v, { v = it }, "Level", default = 100, capHeight = 36.dp) }
        val fader = onNodeWithContentDescription("Level")
        // A tap away from the cap does nothing (no jump to the finger).
        fader.performTouchInput { click(Offset(centerX, 20f)) }
        assertEquals(30, v)
        fader.performTouchInput { swipe(Offset(centerX, bottom - 60f), Offset(centerX, bottom - 160f), 200) }
        assertTrue(v > 30, "dragging up raised it to $v")
        // Double-tap where the cap is now.
        fader.performTouchInput {
            val travel = height - 36f
            val capCentre = (1f - v / 127f) * travel + 18f
            doubleClick(Offset(centerX, capCentre))
        }
        assertEquals(100, v)
    }

    @Test
    fun lampButtonTogglesOnPress() = runDesktopComposeUiTest(200, 100) {
        var on by mutableStateOf(false)
        setContent { LampButton("MUTE", on, { on = it }, DeskColors.Red, Modifier.testTag("lamp")) }
        onNodeWithTag("lamp").performClick()
        assertTrue(on)
        onNodeWithTag("lamp").performClick()
        assertEquals(false, on)
    }
}

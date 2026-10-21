package io.github.l6desk.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.l6desk.App
import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.Control
import io.github.l6desk.model.GlobalParam
import io.github.l6desk.persistence.AppStorage
import io.github.l6desk.persistence.MemoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The SENDS view in the full app, against the simulator. */
@OptIn(ExperimentalTestApi::class)
class SendsUiTest {
    private fun ch(n: Int, p: ChannelParam) = Control.Channel(n, p)
    private val lit = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On")
    private val unlit = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off")

    @Test
    fun sendsViewFadersSetTheSelectedSend() = runDesktopComposeUiTest(1340, 800) {
        val sim = SimulatorTransport()
        val store = MemoryStore()
        setContent { App(listOf(sim), AppStorage(store)) }
        waitUntil { sim.state.value is ConnectionState.Connected }

        onNodeWithTag("sends-button").performClick()
        onNodeWithTag("back-to-mix").assertIsDisplayed()
        onNodeWithTag("send-AUX1").assert(lit)
        onNodeWithTag("send-EFX_SEND").assert(unlit)
        onNodeWithContentDescription("Channel 1 AUX 1")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "unknown"))
        assertEquals(0, onAllNodes(hasTestTag("ch1-pan")).fetchSemanticsNodes().size, "EQ/pan replaced by the send overview")

        // Drag CH1's fader: it sets CH1's AUX 1 send (CC 49), not its level.
        onNodeWithContentDescription("Channel 1 AUX 1").performTouchInput { swipe(Offset(centerX, bottom - 40f), Offset(centerX, bottom - 160f), 300) }
        waitForIdle()
        val aux1 = sim.valueOf(ch(1, ChannelParam.AUX1))
        assertTrue(aux1 != null && aux1 > 0, "AUX 1 send went out: $aux1")
        assertNull(sim.valueOf(ch(1, ChannelParam.LEVEL)), "the level wasn't touched")
        onNode(hasTestTag("ch1-overview-AUX1") and hasText("$aux1", substring = true)).assertExists()

        // EFX: faders set EFX sends; pick the effect type and the compressor.
        onNodeWithTag("send-EFX_SEND").performClick()
        onNodeWithContentDescription("Channel 2 EFX").performTouchInput { swipe(Offset(centerX, bottom - 40f), Offset(centerX, bottom - 120f), 300) }
        onNodeWithTag("efx-ROOM").performClick()
        onNodeWithTag("comp").performClick()
        waitForIdle()
        assertTrue((sim.valueOf(ch(2, ChannelParam.EFX_SEND)) ?: 0) > 0)
        assertEquals(43, sim.valueOf(Control.Global(GlobalParam.EFX_TYPE)))
        assertEquals(127, sim.valueOf(Control.Global(GlobalParam.COMPRESSOR)))
        onNodeWithTag("efx-ROOM").assert(lit)
    }

    @Test
    fun instantMuteStaysToHandAndBackToMixRemembersTheSend() = runDesktopComposeUiTest(1340, 800) {
        val sim = SimulatorTransport()
        val store = MemoryStore()
        setContent { App(listOf(sim), AppStorage(store)) }
        waitUntil { sim.state.value is ConnectionState.Connected }

        onNodeWithTag("sends-button").performClick()
        onNodeWithTag("send-SUB_MIX").performClick()
        onNodeWithTag("master-mute").performClick()
        waitForIdle()
        assertTrue((1..8).all { sim.valueOf(ch(it, ChannelParam.MUTE)) == 127 }, "instant mute works in the SENDS view")
        onNodeWithTag("master-mute").performClick()

        onNodeWithTag("back-to-mix").performClick()
        onNodeWithTag("ch1-level").assertExists()
        onNodeWithTag("sends-button").assertExists()
        assertEquals(ChannelParam.SUB_MIX, AppStorage(store).loadSettings().lastSend)

        onNodeWithTag("sends-button").performClick()
        onNodeWithTag("send-SUB_MIX").assert(lit)
    }

    @Test
    fun aSendTurnedOnTheMixerShowsUp() = runDesktopComposeUiTest(1340, 800) {
        val sim = SimulatorTransport()
        setContent { App(listOf(sim), AppStorage(MemoryStore())) }
        waitUntil { sim.state.value is ConnectionState.Connected }
        onNodeWithTag("sends-button").performClick()
        onNodeWithTag("send-AUX2").performClick()
        sim.moveHardwareControl(ch(3, ChannelParam.AUX2), 64)
        waitForIdle()
        onNodeWithContentDescription("Channel 3 AUX 2").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "64"))
        onNode(hasTestTag("ch3-overview-AUX2") and hasText("64", substring = true)).assertExists()
    }
}

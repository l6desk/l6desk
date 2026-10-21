package io.github.l6desk.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.hasAnyAncestor
import io.github.l6desk.App
import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.Control
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The whole app against the simulator: the desk drives the "mixer" and follows its hardware. */
@OptIn(ExperimentalTestApi::class)
class MixerScreenUiTest {
    private fun ch(n: Int, p: ChannelParam) = Control.Channel(n, p)

    @Test
    fun deskDrivesTheSimulatorAndFollowsHardwareMoves() = runDesktopComposeUiTest(1340, 800) {
        val sim = SimulatorTransport()
        setContent { App(listOf(sim)) }
        waitUntil { sim.state.value is ConnectionState.Connected }
        waitForIdle()
        assertTrue(sim.received.isEmpty(), "nothing is sent until SEND DESK")

        onNodeWithTag("send-desk").performClick()
        waitForIdle()
        assertEquals(100, sim.valueOf(ch(1, ChannelParam.LEVEL)))

        onNodeWithTag("ch3-mute").performClick()
        waitForIdle()
        assertEquals(127, sim.valueOf(ch(3, ChannelParam.MUTE)))

        onNodeWithTag("ch2-solo").performClick()
        waitForIdle()
        assertEquals(127, sim.valueOf(ch(1, ChannelParam.MUTE)))
        assertEquals(0, sim.valueOf(ch(2, ChannelParam.MUTE)))
        onNodeWithTag("clear-solo").performClick()
        waitForIdle()
        assertEquals(0, sim.valueOf(ch(1, ChannelParam.MUTE)))
        assertEquals(127, sim.valueOf(ch(3, ChannelParam.MUTE)), "the user's own mute stays")

        onNodeWithTag("master-mute").performClick()
        waitForIdle()
        assertTrue((1..8).all { sim.valueOf(ch(it, ChannelParam.MUTE)) == 127 })
        onNodeWithTag("master-mute-frame").assertIsDisplayed()
        onNodeWithTag("master-mute").performClick()
        waitForIdle()

        // Someone turns CH2's pan hard left on the mixer itself.
        sim.moveHardwareControl(ch(2, ChannelParam.PAN), 0)
        waitForIdle()
        onNode(hasContentDescription("PAN") and hasAnyAncestor(hasTestTag("ch2-pan")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Left 64"))
    }

    @Test
    fun theHardwareCannotMoveAFaderWhileItIsHeld() = runDesktopComposeUiTest(1340, 800) {
        val sim = SimulatorTransport()
        setContent { App(listOf(sim)) }
        waitUntil { sim.state.value is ConnectionState.Connected }
        val fader = onNodeWithContentDescription("Channel 1 level")
        fun faderValue() = fader.fetchSemanticsNode().config[SemanticsProperties.StateDescription]

        fader.performTouchInput { down(Offset(centerX, bottom - 40f)) }
        waitForIdle()
        sim.moveHardwareControl(ch(1, ChannelParam.LEVEL), 5)
        waitForIdle()
        assertEquals("100", faderValue(), "held by the user: the hardware move is ignored")

        fader.performTouchInput { up() }
        mainClock.advanceTimeBy(500)
        sim.moveHardwareControl(ch(1, ChannelParam.LEVEL), 5)
        waitForIdle()
        assertEquals("5", faderValue(), "released: the hardware move applies")
    }

    @Test
    fun connectionPanelListsDevicesAndSwitches() = runDesktopComposeUiTest(1340, 800) {
        val usb = io.github.l6desk.midi.FakeTransport(
            io.github.l6desk.midi.FakeTransport.usb("L6max [hw:4,0,0]"),
            io.github.l6desk.midi.FakeTransport.usb("L6max [hw:4,0,1]"),
        )
        val sim = SimulatorTransport()
        setContent { App(listOf(usb, sim)) }
        waitUntil { usb.state.value is ConnectionState.Connected }
        assertEquals("L6max [hw:4,0,1]", (usb.state.value as ConnectionState.Connected).device.id, "auto-picked the Mixer Control port")

        onNodeWithTag("connection-button").performClick()
        onNodeWithTag("connection-panel").assertIsDisplayed()
        onNodeWithTag("device-${SimulatorTransport.DEVICE.id}").performClick()
        waitUntil { sim.state.value is ConnectionState.Connected }
        assertEquals(ConnectionState.Disconnected, usb.state.value)
        assertEquals(0, onAllNodes(hasTestTag("connection-panel")).fetchSemanticsNodes().size, "panel closes after picking")

        onNodeWithTag("midi-log-toggle").performClick()
        onNodeWithTag("midi-log").assertIsDisplayed()
    }

    @Test
    fun monoX2OnlyOnChannels5And6() = runDesktopComposeUiTest(1340, 800) {
        val sim = SimulatorTransport()
        setContent { App(listOf(sim)) }
        waitUntil { sim.state.value is ConnectionState.Connected }
        onNodeWithTag("send-desk").performClick()
        onNodeWithTag("ch5-mono").performClick()
        waitForIdle()
        assertEquals(127, sim.valueOf(ch(5, ChannelParam.MONO_X2)))
        assertEquals(0, onAllNodes(hasTestTag("ch1-mono")).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodes(hasTestTag("ch7-mono")).fetchSemanticsNodes().size)
    }

    @Test
    fun portraitShowsBanksOfFourAndTheOtherBanksMutesAndSolos() = runDesktopComposeUiTest(800, 1340) {
        val sim = SimulatorTransport()
        setContent { App(listOf(sim)) }
        waitUntil(timeoutMillis = 3_000) { sim.state.value is ConnectionState.Connected }
        onNodeWithTag("bank-bar").assertExists()
        onNodeWithTag("ch1-level").assertExists()
        onNodeWithTag("ch5-level").assertDoesNotExist()

        // Mute CH2 and solo CH3: the lamps show it, for both banks.
        onNodeWithTag("ch2-mute").performClick()
        onNodeWithTag("ch3-solo").performClick()
        waitForIdle()
        onNodeWithTag("bank-1").performClick()
        onNodeWithTag("ch5-level").assertExists()
        onNodeWithTag("ch1-level").assertDoesNotExist()
        onNodeWithTag("ch8-mute").performClick() // works as in landscape
        waitUntil(timeoutMillis = 3_000) { sim.valueOf(Control.Channel(8, ChannelParam.MUTE)) == 127 }
        onNodeWithTag("bank-0").performClick()
        onNodeWithTag("ch1-level").assertExists()
    }

    @Test
    fun landscapeHasNoBanks() = runDesktopComposeUiTest(1340, 800) {
        setContent { App(listOf(SimulatorTransport())) }
        onNodeWithTag("bank-bar").assertDoesNotExist()
        onNodeWithTag("ch1-level").assertExists()
        onNodeWithTag("ch8-level").assertExists()
    }
}

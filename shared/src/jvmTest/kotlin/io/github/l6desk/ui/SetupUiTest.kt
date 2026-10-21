package io.github.l6desk.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.l6desk.App
import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.Control
import io.github.l6desk.model.L6maxLayout
import io.github.l6desk.persistence.AppStorage
import io.github.l6desk.persistence.MemoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Milestone 8 in the full app: renaming, the CC map editor, Learn, reading the map, saving. */
@OptIn(ExperimentalTestApi::class)
class SetupUiTest {
    private fun ch(n: Int, p: ChannelParam) = Control.Channel(n, p)

    @Test
    fun renamingAChannelIsShownAndSaved() = runDesktopComposeUiTest(1340, 800) {
        val store = MemoryStore()
        val sim = SimulatorTransport()
        setContent { App(listOf(sim), AppStorage(store)) }
        onNodeWithTag("ch2-name").performClick()
        onNodeWithTag("rename-field").performTextReplacement("Guitar")
        onNodeWithTag("rename-ok").performClick()
        waitForIdle()
        onNode(hasTestTag("ch2-name") and hasText("Guitar", substring = true)).assertExists()
        // The desk is saved half a second after the last change.
        waitUntil(timeoutMillis = 5_000) { AppStorage(store).loadDesk().channel(2).name == "Guitar" }
    }

    @Test
    fun editingTheFactoryMapCopiesItToANewProfile() = runDesktopComposeUiTest(1340, 800) {
        val store = MemoryStore()
        setContent { App(listOf(SimulatorTransport()), AppStorage(store)) }
        onNodeWithTag("setup-button").performClick()
        onNodeWithTag("map-1-PAN").performClick()
        onNode(hasText("+1")).performClick() // 73 -> 74 (now a duplicate of CH2 PAN)
        waitForIdle()
        val lib = AppStorage(store).loadProfiles()
        assertEquals("My L6max", lib.activeName)
        assertEquals(74, lib.active.ccMap[ch(1, ChannelParam.PAN)])
        assertEquals(L6maxLayout.FACTORY_CC_MAP, lib.profiles.first().ccMap, "the factory profile is untouched")
        onNodeWithTag("profile-My L6max").assertIsDisplayed()
    }

    @Test
    fun learnAssignsTheMovedControlAndMovesToTheNextChannel() = runDesktopComposeUiTest(1340, 800) {
        // A simulated mixer whose CH1 pan is on CC 107 (not the factory 73).
        val sim = SimulatorTransport(ccMap = L6maxLayout.FACTORY_CC_MAP.with(ch(1, ChannelParam.PAN), 107))
        val store = MemoryStore()
        setContent { App(listOf(sim), AppStorage(store)) }
        waitUntil { sim.state.value is ConnectionState.Connected }
        onNodeWithTag("setup-button").performClick()
        onNodeWithTag("map-1-PAN").performClick()
        onNodeWithTag("learn").performClick()
        waitForIdle()
        sim.moveHardwareControl(ch(1, ChannelParam.PAN), 70)
        waitForIdle()
        assertEquals(107, AppStorage(store).loadProfiles().active.ccMap[ch(1, ChannelParam.PAN)])
        onNodeWithTag("setup-status", useUnmergedTree = true).assertExists()
        assertTrue(onAllNodes(hasText("Move CH2 PAN", substring = true)).fetchSemanticsNodes().isNotEmpty(), "now waiting for CH2")
        onNodeWithTag("learn-cancel").performClick()
        waitForIdle()
        onNodeWithTag("learn").assertExists()
    }

    @Test
    fun readFromMixerLoadsTheMixersMap() = runDesktopComposeUiTest(1340, 800) {
        val custom = L6maxLayout.FACTORY_CC_MAP.with(ch(3, ChannelParam.LEVEL), 107)
        val sim = SimulatorTransport(ccMap = custom)
        val store = MemoryStore()
        setContent { App(listOf(sim), AppStorage(store)) }
        waitUntil { sim.state.value is ConnectionState.Connected }
        onNodeWithTag("setup-button").performClick()
        onNodeWithTag("read-map").performClick()
        waitUntil { AppStorage(store).loadProfiles().active.ccMap == custom }
        assertTrue(AppStorage(store).loadProfiles().active.verified)
        onNodeWithTag("map-3-LEVEL").assertExists()
    }

    @Test
    fun optionsAreSavedAndAutoPushWorksOnTheNextStart() = runDesktopComposeUiTest(1340, 800) {
        val store = MemoryStore()
        AppStorage(store).saveSettings(io.github.l6desk.persistence.AppSettings(autoPushOnConnect = true))
        val sim = SimulatorTransport()
        setContent { App(listOf(sim), AppStorage(store)) }
        waitUntil { sim.state.value is ConnectionState.Connected }
        waitForIdle()
        assertEquals(100, sim.valueOf(ch(1, ChannelParam.LEVEL)), "the desk was sent on connect")
        onNodeWithTag("setup-button").performClick()
        onNodeWithTag("option-midilog").performScrollTo().performClick()
        waitForIdle()
        assertTrue(AppStorage(store).loadSettings().showMidiLog)
        onNodeWithTag("midi-log").assertIsDisplayed()
    }

    @Test
    fun resetMixAsksFirstThenGivesACleanSlate() = runDesktopComposeUiTest(1340, 800) {
        val sim = SimulatorTransport()
        setContent { App(listOf(sim), AppStorage(MemoryStore())) }
        waitUntil { sim.state.value is ConnectionState.Connected }
        onNodeWithTag("ch1-mute").performClick()
        onNodeWithTag("setup-button").performClick()

        onNodeWithTag("reset-mix").performScrollTo().performClick()
        onNodeWithTag("reset-mix-cancel").performScrollTo().performClick()
        waitForIdle()
        assertEquals(null, sim.valueOf(ch(1, ChannelParam.LEVEL)), "cancel sends nothing")

        onNodeWithTag("reset-mix").performScrollTo().performClick()
        onNodeWithTag("reset-mix-confirm").performScrollTo().performClick()
        waitForIdle()
        for (n in 1..8) {
            assertEquals(0, sim.valueOf(ch(n, ChannelParam.LEVEL)))
            assertEquals(0, sim.valueOf(ch(n, ChannelParam.EFX_SEND)))
        }
        assertEquals(0, sim.valueOf(ch(1, ChannelParam.MUTE)), "mutes cleared")
    }
}

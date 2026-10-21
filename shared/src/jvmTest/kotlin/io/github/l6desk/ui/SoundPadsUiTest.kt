package io.github.l6desk.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.l6desk.App
import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.MidiMessage
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.model.L6maxLayout
import io.github.l6desk.persistence.AppStorage
import io.github.l6desk.persistence.MemoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sound pads in the MAIN master section, and their notes in the map editor. */
@OptIn(ExperimentalTestApi::class)
class SoundPadsUiTest {
    @Test
    fun aPadPlaysWhileHeldLikeTheHardwarePad() = runDesktopComposeUiTest(1340, 800) {
        val sim = SimulatorTransport()
        setContent { App(listOf(sim), AppStorage(MemoryStore())) }
        waitUntil { sim.state.value is ConnectionState.Connected }
        onNodeWithTag("pad-1").assertIsDisplayed()
        onNodeWithTag("pad-4").assertIsDisplayed()
        onNodeWithTag("master-level").assertIsDisplayed()
        sim.received.clear()

        onNodeWithTag("pad-3").performTouchInput { down(center) }
        waitForIdle()
        assertEquals(listOf<MidiMessage>(MidiMessage.NoteOn(1, 64, 100)), sim.received, "Note On as the finger goes down")
        onNodeWithTag("pad-3").performTouchInput { up() }
        waitForIdle()
        assertEquals(listOf<MidiMessage>(MidiMessage.NoteOn(1, 64, 100), MidiMessage.NoteOff(1, 64, 64)), sim.received)
        assertEquals(listOf(3), sim.padsPlayed)
    }

    @Test
    fun instantMuteWarnsThatThePadsAreNotMuted() = runDesktopComposeUiTest(1340, 800) {
        val sim = SimulatorTransport()
        setContent { App(listOf(sim), AppStorage(MemoryStore())) }
        waitUntil { sim.state.value is ConnectionState.Connected }
        onNodeWithTag("pads-label").assertTextEquals("SOUND PADS")
        onNodeWithTag("master-mute").performClick()
        waitForIdle()
        onNodeWithTag("pads-label").assertTextEquals("PADS NOT MUTED")
    }

    @Test
    fun padNotesCanBeLearnedFromThePadsOnTheMixer() = runDesktopComposeUiTest(1340, 800) {
        // A mixer whose pad 2 was set to note 48 in L6 Editor.
        val sim = SimulatorTransport(ccMap = L6maxLayout.FACTORY_CC_MAP.withPadNote(2, 48))
        val store = MemoryStore()
        setContent { App(listOf(sim), AppStorage(store)) }
        waitUntil { sim.state.value is ConnectionState.Connected }
        onNodeWithTag("setup-button").performClick()
        onNodeWithTag("map-pad-2").performClick()
        onNodeWithTag("learn").performClick()
        waitForIdle()
        sim.pressHardwarePad(2, true)
        waitForIdle()
        assertEquals(48, AppStorage(store).loadProfiles().active.ccMap.padNote(2))
        assertTrue(onAllNodes(hasText("Press sound pad 3", substring = true)).fetchSemanticsNodes().isNotEmpty(), "now waiting for pad 3")
        onNodeWithTag("learn-cancel").performClick()
    }

    @Test
    fun readFromMixerPicksUpThePadNotes() = runDesktopComposeUiTest(1340, 800) {
        val custom = L6maxLayout.FACTORY_CC_MAP.withPadNote(1, 36).withPadNote(4, null)
        val sim = SimulatorTransport(ccMap = custom)
        val store = MemoryStore()
        setContent { App(listOf(sim), AppStorage(store)) }
        waitUntil { sim.state.value is ConnectionState.Connected }
        onNodeWithTag("setup-button").performClick()
        onNodeWithTag("read-map").performClick()
        waitUntil { AppStorage(store).loadProfiles().active.ccMap.padNotes == listOf(36, 62, 64, null) }
    }
}

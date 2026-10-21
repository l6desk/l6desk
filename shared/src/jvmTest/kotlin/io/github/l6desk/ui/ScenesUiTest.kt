package io.github.l6desk.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.l6desk.App
import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.Control
import io.github.l6desk.model.MixerState
import io.github.l6desk.model.SceneBank
import io.github.l6desk.model.SceneSlot
import io.github.l6desk.persistence.AppStorage
import io.github.l6desk.persistence.MemoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** App scenes in the SENDS view: hold to store, tap twice to recall. */
@OptIn(ExperimentalTestApi::class)
class ScenesUiTest {
    private fun ch(n: Int, p: ChannelParam) = Control.Channel(n, p)
    private fun state(s: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, s)

    @Test
    fun holdStoresAndASecondTapRecalls() = runDesktopComposeUiTest(1340, 800) {
        val sim = SimulatorTransport()
        val store = MemoryStore()
        // Scene B already holds a quiet verse: CH1 at 20.
        AppStorage(store).saveScenes(SceneBank().store(SceneSlot.B, MixerState().withChannel(1) { it.with(ChannelParam.LEVEL, 20) }))
        setContent { App(listOf(sim), AppStorage(store)) }
        waitUntil { sim.state.value is ConnectionState.Connected }
        onNodeWithTag("sends-button").performClick()

        onNodeWithTag("scene-A").assert(state("empty"))
        onNodeWithTag("scene-B").assert(state("stored"))

        // Hold A: stores the current desk (CH1 at its default 100).
        onNodeWithTag("scene-A").performTouchInput { longClick(durationMillis = 900) }
        waitForIdle()
        onNodeWithTag("scene-A").assert(state("current"))
        assertEquals(setOf(SceneSlot.A, SceneSlot.B), AppStorage(store).loadScenes().scenes.keys, "saved")

        // One tap on B only arms it: nothing is sent.
        sim.received.clear()
        onNodeWithTag("scene-B").performClick()
        waitForIdle()
        onNodeWithTag("scene-B").assert(state("armed"))
        assertTrue(sim.received.isEmpty(), "a single tap sends nothing")

        // The second tap recalls and sends the scene.
        onNodeWithTag("scene-B").performClick()
        waitForIdle()
        onNodeWithTag("scene-B").assert(state("current"))
        assertEquals(20, sim.valueOf(ch(1, ChannelParam.LEVEL)), "the scene's CH1 level (master at unity)")
    }

    @Test
    fun anArmedSceneTimesOutAndEmptyScenesDoNothing() = runDesktopComposeUiTest(1340, 800) {
        val sim = SimulatorTransport()
        val store = MemoryStore()
        AppStorage(store).saveScenes(SceneBank().store(SceneSlot.C, MixerState().withChannel(2) { it.withMute(true) }))
        setContent { App(listOf(sim), AppStorage(store)) }
        waitUntil { sim.state.value is ConnectionState.Connected }
        onNodeWithTag("sends-button").performClick()

        onNodeWithTag("scene-D").performClick()
        waitForIdle()
        onNodeWithTag("scene-D").assert(state("empty"))

        onNodeWithTag("scene-C").performClick()
        waitForIdle()
        onNodeWithTag("scene-C").assert(state("armed"))
        mainClock.advanceTimeBy(3_500)
        waitForIdle()
        onNodeWithTag("scene-C").assert(state("stored"))
        sim.received.clear()
        onNodeWithTag("scene-C").performClick() // arms again, doesn't recall
        waitForIdle()
        assertTrue(sim.received.isEmpty(), "after the timeout a single tap only re-arms")
    }

    @Test
    fun aSceneRecalledOnTheMixerWarnsThatTheDeskIsOutOfStep() = runDesktopComposeUiTest(1340, 800) {
        val sim = SimulatorTransport()
        setContent { App(listOf(sim), AppStorage(MemoryStore())) }
        waitUntil { sim.state.value is ConnectionState.Connected }
        onNodeWithTag("send-desk").performClick()
        waitForIdle()
        sim.recallHardwareScene(0)
        waitForIdle()
        onNode(hasAnyAncestor(hasTestTag("scene-notice")) and hasText("Scene A recalled on the L6max", substring = true)).assertExists()
        onNodeWithTag("send-desk").assert(state("On"))

        sim.received.clear()
        onNodeWithTag("scene-notice-send").performClick()
        waitForIdle()
        assertEquals(0, onAllNodes(hasTestTag("scene-notice")).fetchSemanticsNodes().size, "SEND DESK clears the warning")
        assertEquals(100, sim.valueOf(ch(1, ChannelParam.LEVEL)), "the desk went back to the mixer")
    }

    @Test
    fun linkedMixerSceneButtonsRecallTheAppScenes() = runDesktopComposeUiTest(1340, 800) {
        val sim = SimulatorTransport()
        val store = MemoryStore()
        AppStorage(store).saveSettings(io.github.l6desk.persistence.AppSettings(linkMixerScenes = true))
        AppStorage(store).saveScenes(SceneBank().store(SceneSlot.A, MixerState().withChannel(1) { it.with(ChannelParam.LEVEL, 20) }))
        setContent { App(listOf(sim), AppStorage(store)) }
        waitUntil { sim.state.value is ConnectionState.Connected }

        sim.recallHardwareScene(0) // scene A on the mixer
        waitForIdle()
        assertEquals(20, sim.valueOf(ch(1, ChannelParam.LEVEL)), "the app's scene A was sent")
        onNode(hasAnyAncestor(hasTestTag("scene-notice")) and hasText("app scene A recalled", substring = true)).assertExists()
        mainClock.advanceTimeBy(4_500)
        waitForIdle()
        assertEquals(0, onAllNodes(hasTestTag("scene-notice")).fetchSemanticsNodes().size, "the confirmation goes by itself")

        sim.recallHardwareScene(1) // scene B: no app scene stored there
        waitForIdle()
        onNode(hasAnyAncestor(hasTestTag("scene-notice")) and hasText("Scene B recalled on the L6max", substring = true)).assertExists()
    }
}

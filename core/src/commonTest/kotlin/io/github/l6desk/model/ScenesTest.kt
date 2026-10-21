package io.github.l6desk.model

import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.persistence.AppStorage
import io.github.l6desk.persistence.MemoryStore
import io.github.l6desk.sync.MixerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScenesTest {
    private val verse = MixerState()
        .withChannel(1) { it.copy(name = "Vocal", solo = true).with(ChannelParam.LEVEL, 110).with(ChannelParam.AUX1, 90) }
        .withChannel(3) { it.withMute(true).with(ChannelParam.EQ_LOW, 30) }
        .withGlobal(GlobalParam.EFX_TYPE, EfxType.ROOM.value)
        .copy(master = 90, masterMute = true)

    @Test
    fun aSceneLeavesOutSoloAndInstantMute() {
        val bank = SceneBank().store(SceneSlot.A, verse)
        val a = bank[SceneSlot.A]!!
        assertFalse(a.channel(1).solo)
        assertFalse(a.masterMute)
        assertEquals(110, a.channel(1).level)
        assertNull(bank[SceneSlot.B])
        assertNull(bank.clear(SceneSlot.A)[SceneSlot.A])
    }

    @Test
    fun recallTakesTheMixButKeepsNamesInstantMuteAndSendsTheSceneDidntKnow() {
        val scene = SceneBank().store(SceneSlot.A, verse)[SceneSlot.A]!!
        val now = MixerState()
            .withChannel(1) { it.copy(name = "Lead vox", solo = true).with(ChannelParam.AUX2, 50) }
            .withChannel(2) { it.with(ChannelParam.AUX1, 77) }
            .copy(masterMute = true)
        val r = now.recall(scene)
        assertEquals(110, r.channel(1).level)
        assertEquals("Lead vox", r.channel(1).name, "names label the inputs, not the mix")
        assertFalse(r.channel(1).solo)
        assertTrue(r.masterMute, "a recall never releases instant mute")
        assertTrue(r.channel(3).mute)
        assertEquals(90, r.channel(1).send(ChannelParam.AUX1), "the scene's known send")
        assertEquals(50, r.channel(1).send(ChannelParam.AUX2), "a send the scene didn't know keeps its value")
        assertEquals(77, r.channel(2).send(ChannelParam.AUX1))
        assertEquals(90, r.master)
        assertEquals(43, r.globals[GlobalParam.EFX_TYPE])
    }

    @Test
    fun recallSendsTheWholeSceneSoTheMixerMatches() = runTest {
        val sim = SimulatorTransport()
        val c = MixerController(sim, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        sim.open(SimulatorTransport.DEVICE)
        c.pushAll()
        advanceTimeBy(1_000)
        val scene = SceneBank().store(SceneSlot.B, verse)[SceneSlot.B]!!
        assertTrue(c.recallScene(scene))
        assertEquals(scene.effectiveLevel(1), sim.valueOf(Control.Channel(1, ChannelParam.LEVEL)))
        assertEquals(127, sim.valueOf(Control.Channel(3, ChannelParam.MUTE)))
        assertEquals(30, sim.valueOf(Control.Channel(3, ChannelParam.EQ_LOW)))
        assertEquals(90, sim.valueOf(Control.Channel(1, ChannelParam.AUX1)))
        assertEquals(43, sim.valueOf(Control.Global(GlobalParam.EFX_TYPE)))
        assertFalse(c.needsPush.value)
    }

    @Test
    fun recallWhileDisconnectedOnlyChangesTheDesk() = runTest {
        val sim = SimulatorTransport()
        val c = MixerController(sim, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        assertFalse(c.recallScene(verse.asSceneSnapshot()))
        assertEquals(110, c.state.value.channel(1).level)
        assertTrue(sim.received.isEmpty())
    }

    @Test
    fun scenesAreSavedAndLoaded() {
        val store = MemoryStore()
        val storage = AppStorage(store)
        storage.saveScenes(SceneBank().store(SceneSlot.A, verse).store(SceneSlot.D, MixerState()))
        val loaded = storage.loadScenes()
        assertEquals(setOf(SceneSlot.A, SceneSlot.D), loaded.scenes.keys)
        assertEquals(110, loaded[SceneSlot.A]!!.channel(1).level)
        assertEquals(90, loaded[SceneSlot.A]!!.channel(1).send(ChannelParam.AUX1))
        assertEquals(43, loaded[SceneSlot.A]!!.globals[GlobalParam.EFX_TYPE])
        assertTrue(store.files.getValue(AppStorage.SCENES).contains("\"A\""))
    }

    @Test
    fun mixerSceneRecallsAreReportedAndOthersIgnored() = runTest {
        val sim = SimulatorTransport()
        val c = MixerController(sim, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        sim.open(SimulatorTransport.DEVICE)
        val heard = mutableListOf<SceneSlot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.mixerSceneRecalls.collect { heard += it } }
        c.pushAll()
        sim.recallHardwareScene(0)
        sim.recallHardwareScene(1)
        sim.recallHardwareScene(9) // not a scene
        assertEquals(listOf(SceneSlot.A, SceneSlot.B), heard)
        assertFalse(c.needsPush.value)
        c.markMixerChanged()
        assertTrue(c.needsPush.value, "SEND DESK lights after the mixer changed behind the desk")
    }

    @Test
    fun linkSettingIsSaved() {
        val storage = AppStorage(MemoryStore())
        storage.saveSettings(io.github.l6desk.persistence.AppSettings(linkMixerScenes = true))
        assertTrue(storage.loadSettings().linkMixerScenes)
    }
}

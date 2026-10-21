package io.github.l6desk.sync

import io.github.l6desk.midi.FakeTransport
import io.github.l6desk.midi.HardwareCaptures
import io.github.l6desk.midi.L6maxSysEx
import io.github.l6desk.midi.MidiMessage
import io.github.l6desk.midi.MidiStreamParser
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.midi.parseHex
import io.github.l6desk.model.L6maxLayout
import io.github.l6desk.model.Profile
import io.github.l6desk.persistence.AppStorage
import io.github.l6desk.persistence.MemoryStore
import io.github.l6desk.persistence.ProfileLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SoundPadsTest {
    private fun TestScope.rig(sim: SimulatorTransport = SimulatorTransport()): Pair<MixerController, SimulatorTransport> {
        val c = MixerController(sim, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        return c to sim
    }

    @Test
    fun parsesTheNotesThePadsSend() {
        // Captured through the Pi bridge: pad 1 pressed and released.
        val parser = MidiStreamParser()
        val messages = parser.feed(parseHex("90 3C 64 80 3C 40 90 3E 00"))
        assertEquals(
            listOf(MidiMessage.NoteOn(1, 60, 100), MidiMessage.NoteOff(1, 60, 64), MidiMessage.NoteOff(1, 62, 0)),
            messages,
        )
        assertEquals(parseHex("90 3C 64"), MidiMessage.NoteOn(1, 60, 100).toBytes())
        assertEquals(parseHex("80 3C 40"), MidiMessage.NoteOff(1, 60).toBytes())
    }

    @Test
    fun theRealSnapshotHoldsTheFactoryPadNotes() {
        val map = L6maxSysEx.parseCcMap(MidiMessage.SysEx(HardwareCaptures.EDITOR_OPEN_REPLY))
        assertEquals(listOf(60, 62, 64, 65), map.padNotes)
        assertEquals(3, map.padFor(64))
        assertNull(map.padFor(61))
    }

    @Test
    fun snapshotRoundTripWithCustomAndUnmappedPads() {
        val custom = L6maxLayout.FACTORY_CC_MAP.withPadNote(2, 36).withPadNote(4, null)
        assertEquals(listOf(60, 36, 64, null), L6maxSysEx.parseCcMap(L6maxSysEx.buildSnapshot(custom)).padNotes)
    }

    @Test
    fun aPadPressLastsAsLongAsTheFinger() = runTest {
        val (c, sim) = rig()
        sim.open(SimulatorTransport.DEVICE)
        c.setPad(1, true)
        assertEquals(setOf(1), c.pads.value)
        c.setPad(1, true) // a repeat while held sends nothing more
        c.setPad(1, false)
        assertEquals(emptySet(), c.pads.value)
        c.setPad(1, false)
        assertEquals(listOf(MidiMessage.NoteOn(1, 60, 100), MidiMessage.NoteOff(1, 60, 64)), sim.received)
        assertEquals(listOf(1), sim.padsPlayed)
    }

    @Test
    fun padsUseTheProfilesNotesAndChannelAndUnmappedPadsDoNothing() = runTest {
        val usb = FakeTransport(FakeTransport.usb("L6max [hw:4,0,1]"))
        val c = MixerController(usb, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        usb.open(usb.devices.value.first())
        c.profile = Profile("Mine", L6maxLayout.FACTORY_CC_MAP.withPadNote(3, 48).withPadNote(4, null), midiChannel = 5)
        c.setPad(3, true)
        c.setPad(4, true)
        c.setPad(3, false)
        assertEquals(listOf<MidiMessage>(MidiMessage.NoteOn(5, 48, 100), MidiMessage.NoteOff(5, 48, 64)), usb.sent)
        assertEquals(emptySet(), c.pads.value)
    }

    @Test
    fun padsPressedOnTheMixerLightWhileHeld() = runTest {
        val (c, sim) = rig()
        sim.open(SimulatorTransport.DEVICE)
        sim.pressHardwarePad(2, true)
        sim.pressHardwarePad(4, true)
        assertEquals(setOf(2, 4), c.pads.value)
        sim.pressHardwarePad(2, false)
        assertEquals(setOf(4), c.pads.value)
        sim.close()
        assertEquals(emptySet(), c.pads.value, "a lost connection releases every pad")
    }

    @Test
    fun notesOnOtherChannelsOrOtherNotesAreIgnored() = runTest {
        val (c, sim) = rig(SimulatorTransport(midiChannel = 2))
        sim.open(SimulatorTransport.DEVICE)
        sim.pressHardwarePad(1, true) // channel 2; the desk's profile is on channel 1
        assertEquals(emptySet(), c.pads.value)
    }

    @Test
    fun padNotesAreSavedWithTheProfileAndOldFilesGetTheFactoryNotes() {
        val store = MemoryStore()
        val storage = AppStorage(store)
        val mine = Profile("Mine", L6maxLayout.FACTORY_CC_MAP.withPadNote(1, 72).withPadNote(2, null))
        storage.saveProfiles(ProfileLibrary.DEFAULT.save(mine))
        assertEquals(listOf(72, null, 64, 65), storage.loadProfiles().active.ccMap.padNotes)

        store.write(AppStorage.PROFILES, """{"active":"Old","profiles":[{"name":"Old","ccMap":{"CH1:LEVEL":81}}]}""")
        assertEquals(L6maxLayout.FACTORY_PAD_NOTES, storage.loadProfiles().active.ccMap.padNotes)
    }
}

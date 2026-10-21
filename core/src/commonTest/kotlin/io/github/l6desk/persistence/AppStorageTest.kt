package io.github.l6desk.persistence

import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.Control
import io.github.l6desk.model.GlobalParam
import io.github.l6desk.model.L6maxLayout
import io.github.l6desk.model.MixerState
import io.github.l6desk.model.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppStorageTest {
    private val store = MemoryStore()
    private val storage = AppStorage(store)

    @Test
    fun missingFilesGiveDefaults() {
        assertEquals(MixerState(), storage.loadDesk())
        assertEquals(ProfileLibrary.DEFAULT, storage.loadProfiles())
        assertEquals(AppSettings(), storage.loadSettings())
    }

    @Test
    fun deskRoundTripWithoutSoloOrMasterMute() {
        val desk = MixerState()
            .withChannel(1) { it.copy(name = "Vocal", solo = true).with(ChannelParam.LEVEL, 110).withMute(true) }
            .withChannel(5) { it.with(ChannelParam.MONO_X2, 127).with(ChannelParam.EQ_LOW, 30) }
            .copy(master = 95, masterMute = true)
        storage.saveDesk(desk)
        val loaded = storage.loadDesk()
        assertEquals("Vocal", loaded.channel(1).name)
        assertEquals(110, loaded.channel(1).level)
        assertTrue(loaded.channel(1).mute, "mutes are kept")
        assertFalse(loaded.channel(1).solo, "solo starts off")
        assertFalse(loaded.masterMute, "master mute starts off")
        assertTrue(loaded.channel(5).monoX2)
        assertEquals(30, loaded.channel(5)[ChannelParam.EQ_LOW])
        assertEquals(95, loaded.master)
    }

    @Test
    fun profilesRoundTripAndFactoryAlwaysPresent() {
        val custom = Profile("Church hall", L6maxLayout.FACTORY_CC_MAP.with(Control.Channel(1, ChannelParam.PAN), 107), midiChannel = 3)
        storage.saveProfiles(ProfileLibrary.DEFAULT.save(custom))
        assertTrue(store.files.getValue(AppStorage.PROFILES).contains("\"CH1:PAN\": 107"))
        val loaded = storage.loadProfiles()
        assertEquals(listOf(Profile.FACTORY.name, "Church hall"), loaded.profiles.map { it.name })
        assertEquals(custom, loaded.active)
        assertEquals(107, loaded.active.ccMap[Control.Channel(1, ChannelParam.PAN)])
        assertEquals(117, loaded.active.ccMap[Control.Global(GlobalParam.EFX_TYPE)])
    }

    @Test
    fun factoryProfileCannotBeDeletedOrOverwrittenOnDisk() {
        val lib = ProfileLibrary.DEFAULT.delete(Profile.FACTORY.name)
        assertEquals(ProfileLibrary.DEFAULT, lib)
        // A file claiming to be the factory profile is ignored in favour of the built-in one.
        store.write(AppStorage.PROFILES, """{"profiles":[{"name":"L6max factory","ccMap":{"CH1:LEVEL":5}}]}""")
        assertEquals(81, storage.loadProfiles().active.ccMap[Control.Channel(1, ChannelParam.LEVEL)])
    }

    @Test
    fun settingsRoundTrip() {
        val s = AppSettings(
            lastDeviceId = "L6max [hw:4,0,1]", autoPushOnConnect = true, showMidiLog = true,
            bridges = listOf("192.168.1.20", "pi.local:5705"), bridgeMode = true, bridgePort = 5705,
        )
        storage.saveSettings(s)
        assertEquals(s, storage.loadSettings())
    }

    @Test
    fun unreadableFilesAreKeptAsBadAndDefaultsUsed() {
        store.write(AppStorage.DESK, "{not json")
        assertEquals(MixerState(), storage.loadDesk())
        assertEquals("{not json", store.files["desk.json.bad"])
    }

    @Test
    fun invalidEntriesAreSkippedNotFatal() {
        store.write(
            AppStorage.DESK,
            """{"master":300,"channels":[{"number":1,"name":"","params":{"LEVEL":999,"PAN":10,"MONO_X2":127,"BOGUS":1}}]}""",
        )
        val desk = storage.loadDesk()
        assertEquals(127, desk.master, "clamped")
        assertEquals("CH 1", desk.channel(1).name)
        assertEquals(100, desk.channel(1).level, "out of range: default")
        assertEquals(10, desk.channel(1).pan)
        assertFalse(desk.channel(1).monoX2, "MONO x2 doesn't exist on channel 1")

        store.write(AppStorage.PROFILES, """{"profiles":[{"name":"x","midiChannel":1,"ccMap":{"CH1:LEVEL":96,"CH9:LEVEL":5,"CH2:LEVEL":7}}]}""")
        val map = storage.loadProfiles().profiles.single { it.name == "x" }.ccMap
        assertNull(map[Control.Channel(1, ChannelParam.LEVEL)], "96 isn't assignable on the L6max")
        assertEquals(7, map[Control.Channel(2, ChannelParam.LEVEL)])
    }

    @Test
    fun controlKeys() {
        assertEquals("CH3:LEVEL", AppStorage.controlKey(Control.Channel(3, ChannelParam.LEVEL)))
        assertEquals(Control.Channel(5, ChannelParam.MONO_X2), AppStorage.parseControlKey("CH5:MONO_X2"))
        assertEquals(Control.Global(GlobalParam.COMPRESSOR), AppStorage.parseControlKey("COMPRESSOR"))
        assertNull(AppStorage.parseControlKey("CH1:MONO_X2"))
        assertNull(AppStorage.parseControlKey("nonsense"))
    }

    @Test
    fun sendsEffectsAndLastSendRoundTrip() {
        val desk = MixerState()
            .withChannel(1) { it.with(ChannelParam.AUX1, 90).with(ChannelParam.EFX_SEND, 20) }
            .withGlobal(io.github.l6desk.model.GlobalParam.EFX_TYPE, 43)
            .withGlobal(io.github.l6desk.model.GlobalParam.COMPRESSOR, 127)
        storage.saveDesk(desk)
        val loaded = storage.loadDesk()
        assertEquals(90, loaded.channel(1).send(ChannelParam.AUX1))
        assertNull(loaded.channel(1).send(ChannelParam.AUX2), "unknown stays unknown")
        assertEquals(43, loaded.globals[io.github.l6desk.model.GlobalParam.EFX_TYPE])
        assertEquals(127, loaded.globals[io.github.l6desk.model.GlobalParam.COMPRESSOR])

        storage.saveSettings(AppSettings(lastSend = ChannelParam.SUB_MIX))
        assertEquals(ChannelParam.SUB_MIX, storage.loadSettings().lastSend)
        store.write(AppStorage.SETTINGS, """{"lastSend":"LEVEL"}""")
        assertEquals(ChannelParam.AUX1, storage.loadSettings().lastSend, "only sends are valid")
    }

    @Test
    fun theHeldPlayerLevelRoundTripsAndOldFilesStillRestore() {
        storage.saveSettings(AppSettings(playerHeldLevels = mapOf(8 to 90)))
        assertEquals(mapOf(8 to 90), storage.loadSettings().playerHeldLevels)
        // Saved by 0.16 (both CH7 and CH8 held, as a list): still restored after the update.
        store.write(AppStorage.SETTINGS, """{"version":1,"playerHeldLevels":[100,64]}""")
        assertEquals(mapOf(7 to 100, 8 to 64), storage.loadSettings().playerHeldLevels)
        store.write(AppStorage.SETTINGS, """{"version":1,"playerHeld":{"7":300,"3":5}}""")
        assertEquals(mapOf(7 to 127), storage.loadSettings().playerHeldLevels, "only CH7/CH8, in range")
    }
}

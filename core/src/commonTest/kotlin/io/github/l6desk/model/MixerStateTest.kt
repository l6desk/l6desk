package io.github.l6desk.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixerStateTest {
    private val initial = MixerState()
    private fun mutes(s: MixerState) = (1..8).map { s.effectiveMute(it) }

    @Test
    fun defaults() {
        assertEquals(8, initial.channels.size)
        assertEquals(100, initial.channel(1).level)
        assertEquals(64, initial.channel(1).pan)
        assertEquals("CH 3", initial.channel(3).name)
        assertEquals(MixerState.MASTER_UNITY, initial.master, "master starts at unity (6 on the scale)")
        assertEquals(76, MixerState.MASTER_UNITY)
        assertTrue(mutes(initial).none { it })
    }

    @Test
    fun soloMutesEveryOtherChannel() {
        val s = initial.withChannel(2) { it.copy(solo = true) }
        assertEquals(listOf(true, false, true, true, true, true, true, true), mutes(s))
        assertTrue(s.impliedMute(1))
        assertFalse(s.impliedMute(2))
    }

    @Test
    fun multipleSolos() {
        val s = initial.withChannel(2) { it.copy(solo = true) }.withChannel(5) { it.copy(solo = true) }
        assertEquals(listOf(true, false, true, true, false, true, true, true), mutes(s))
    }

    @Test
    fun releasingSoloRestoresUserMutes() {
        val withUserMute = initial.withChannel(4) { it.withMute(true) }
        val soloed = withUserMute.withChannel(1) { it.copy(solo = true) }
        val released = soloed.withChannel(1) { it.copy(solo = false) }
        assertEquals(listOf(false, false, false, true, false, false, false, false), mutes(released))
        assertFalse(released.impliedMute(4), "a user mute is not an implied mute")
    }

    @Test
    fun aSoloedChannelThatIsAlsoMutedStaysMuted() {
        val s = initial.withChannel(1) { it.withMute(true).copy(solo = true) }
        assertTrue(s.effectiveMute(1))
    }

    @Test
    fun masterMuteMutesEverythingAndReleasesToPreviousState() {
        val before = initial.withChannel(3) { it.withMute(true) }.withChannel(6) { it.copy(solo = true) }
        val muted = before.copy(masterMute = true)
        assertTrue(mutes(muted).all { it })
        assertEquals(mutes(before), mutes(muted.copy(masterMute = false)))
    }

    @Test
    fun masterAtUnitySendsFaderValuesUnchanged() {
        assertEquals(100, initial.effectiveLevel(1))
        assertEquals(127, initial.withChannel(1) { it.with(ChannelParam.LEVEL, 127) }.effectiveLevel(1))
    }

    @Test
    fun masterBelowUnityTurnsEverythingDown() {
        assertEquals(50, initial.copy(master = 38).effectiveLevel(1), "half of unity, half the level")
        assertEquals(0, initial.copy(master = 0).effectiveLevel(1))
    }

    @Test
    fun masterAboveUnityBoostsEveryChannel() {
        val s = initial.withChannel(2) { it.with(ChannelParam.LEVEL, 76) }.copy(master = 101)
        assertEquals(101, s.effectiveLevel(2), "fader 6 at master 8 sends 8")
    }

    @Test
    fun aMaxedChannelStaysAt127AndComesBackInProportion() {
        // CH1 at 100 and CH2 at 50: master full up boosts CH1 to 167 internally, sent as 127.
        val s = initial.withChannel(2) { it.with(ChannelParam.LEVEL, 50) }
        val levels = { m: Int -> s.copy(master = m).let { it.effectiveLevel(1) to it.effectiveLevel(2) } }
        assertEquals(127 to 84, levels(127))
        assertEquals(127 to 66, levels(100), "CH1 still above 127 internally (132), so still maxed")
        assertEquals(125 to 63, levels(95), "now below 127: CH1 follows its own level again")
        assertEquals(100 to 50, levels(76), "back at unity, the original balance")
    }

    @Test
    fun masterMuteDoesNotChangeLevels() {
        assertEquals(initial.effectiveLevel(2), initial.copy(masterMute = true).effectiveLevel(2))
    }

    @Test
    fun outboundValues() {
        val s = initial.copy(master = 38, masterMute = true).withChannel(2) { it.with(ChannelParam.PAN, 10) }
        assertEquals(127, s.outboundValue(Control.Channel(1, ChannelParam.MUTE)))
        assertEquals(50, s.outboundValue(Control.Channel(1, ChannelParam.LEVEL)))
        assertEquals(10, s.outboundValue(Control.Channel(2, ChannelParam.PAN)))
        assertEquals(64, s.outboundValue(Control.Channel(2, ChannelParam.EQ_MID)))
        assertEquals(0, s.outboundValue(Control.Global(GlobalParam.COMPRESSOR)))
    }

    @Test
    fun hardwareLevelMapsBackToFaderPosition() {
        val s = initial.copy(master = 38)
        assertEquals(60, s.faderForDeviceLevel(1, 30))
        // ...and that fader position sends the same level back, so nothing is echoed.
        assertEquals(30, s.withChannel(1) { it.with(ChannelParam.LEVEL, 60) }.effectiveLevel(1))
        assertEquals(127, s.faderForDeviceLevel(1, 127), "clamped when the hardware is above what master allows")
        assertNull(initial.copy(master = 0).faderForDeviceLevel(1, 40))
    }

    @Test
    fun aMaxedChannelKeepsItsLevelWhenTheHardwareReportsTheMaximum() {
        val boosted = initial.copy(master = 127) // CH1 at 100 is 167 internally, sent as 127
        assertNull(boosted.faderForDeviceLevel(1, 127), "the hardware can't say how far above 127 it is")
        assertEquals(60, boosted.faderForDeviceLevel(1, 100), "a lower value from the hardware does move it")
    }

    @Test
    fun rejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> { initial.withChannel(1) { it.with(ChannelParam.LEVEL, 128) } }
        assertFailsWith<IllegalArgumentException> { initial.withChannel(1) { it.with(ChannelParam.MONO_X2, 127) } }
        assertFailsWith<IllegalArgumentException> { initial.copy(master = -1) }
    }

    @Test
    fun cleanSlateResetsTheMixButKeepsNamesAndInputSetup() {
        val busy = initial
            .withChannel(1) { it.copy(name = "Vocal", solo = true).with(ChannelParam.LEVEL, 110).with(ChannelParam.EQ_HIGH, 100).withMute(true) }
            .withChannel(2) { it.with(ChannelParam.PAN, 0).with(ChannelParam.EQ_MID_FREQ, 20) }
            .withChannel(5) { it.with(ChannelParam.MONO_X2, 127).with(ChannelParam.EFX_SEND, 90) }
            .copy(master = 120, masterMute = true)
        val clean = busy.cleanSlate()
        for (c in clean.channels) {
            assertEquals(0, c.level)
            assertEquals(64, c.pan)
            for (p in listOf(ChannelParam.EQ_HIGH, ChannelParam.EQ_MID_FREQ, ChannelParam.EQ_MID, ChannelParam.EQ_LOW)) assertEquals(64, c[p])
            assertEquals(0, c[ChannelParam.EFX_SEND])
            assertFalse(c.mute)
            assertFalse(c.solo)
        }
        assertEquals("Vocal", clean.channel(1).name)
        assertTrue(clean.channel(5).monoX2, "input setup is kept")
        assertEquals(MixerState.MASTER_UNITY, clean.master)
        assertFalse(clean.masterMute)
    }
}

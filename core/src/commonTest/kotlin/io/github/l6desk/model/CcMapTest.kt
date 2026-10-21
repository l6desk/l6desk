package io.github.l6desk.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CcMapTest {
    private val factory = L6maxLayout.FACTORY_CC_MAP

    @Test
    fun factoryMapCoversTheWholeTableWithUniqueAssignableNumbers() {
        assertEquals(94, L6maxLayout.TABLE_ORDER.size)
        assertEquals(94, factory.assignments.size)
        assertTrue(factory.duplicates().isEmpty())
        assertTrue(factory.assignments.values.all(L6maxLayout::isAssignableCc))
    }

    @Test
    fun factoryNumbersMatchTheHardware() {
        assertEquals(81, factory[Control.Channel(1, ChannelParam.LEVEL)])
        assertEquals(88, factory[Control.Channel(8, ChannelParam.LEVEL)])
        assertEquals(73, factory[Control.Channel(1, ChannelParam.PAN)])
        assertEquals(1, factory[Control.Channel(1, ChannelParam.EQ_HIGH)])
        assertEquals(11, factory[Control.Channel(1, ChannelParam.EQ_MID_FREQ)])
        assertEquals(21, factory[Control.Channel(1, ChannelParam.EQ_MID)])
        assertEquals(33, factory[Control.Channel(1, ChannelParam.EQ_LOW)])
        assertEquals(
            listOf(93, 94, 95, 102, 103, 104, 105, 106),
            (1..8).map { factory[Control.Channel(it, ChannelParam.MUTE)] },
        )
        assertEquals(109, factory[Control.Channel(5, ChannelParam.MONO_X2)])
        assertEquals(114, factory[Control.Channel(8, ChannelParam.USB_INPUT)])
        assertEquals(119, factory[Control.Global(GlobalParam.COMPRESSOR)])
    }

    @Test
    fun reverseLookup() {
        assertEquals(Control.Channel(3, ChannelParam.LEVEL), factory.controlFor(83))
        assertEquals(Control.Global(GlobalParam.EFX_TYPE), factory.controlFor(117))
        assertNull(factory.controlFor(32))
    }

    @Test
    fun editingAndDuplicates() {
        val ch1Level = Control.Channel(1, ChannelParam.LEVEL)
        val edited = factory.with(ch1Level, 82) // clashes with CH2 level
        assertEquals(listOf(82), edited.duplicates().keys.toList())
        assertEquals(2, edited.duplicates().getValue(82).size)
        val unmapped = factory.with(ch1Level, null)
        assertNull(unmapped[ch1Level])
        assertNull(unmapped.controlFor(81))
    }

    @Test
    fun rejectsCcNumbersTheL6maxCannotUse() {
        for (cc in listOf(0, 32, 96, 101, 120, 127)) {
            assertFalse(L6maxLayout.isAssignableCc(cc))
            assertFailsWith<IllegalArgumentException> { factory.with(Control.Channel(1, ChannelParam.PAN), cc) }
        }
    }

    @Test
    fun monoAndUsbSwitchesOnlyExistOnTheirChannels() {
        assertTrue(ChannelParam.MONO_X2.availableOn(5))
        assertFalse(ChannelParam.MONO_X2.availableOn(7))
        assertTrue(ChannelParam.USB_INPUT.availableOn(8))
        assertFailsWith<IllegalArgumentException> { Control.Channel(1, ChannelParam.MONO_X2) }
        assertFailsWith<IllegalArgumentException> { Control.Channel(9, ChannelParam.LEVEL) }
    }

    @Test
    fun profileValidatesMidiChannel() {
        assertEquals(1, Profile.FACTORY.midiChannel)
        assertTrue(Profile.FACTORY.verified)
        assertFailsWith<IllegalArgumentException> { Profile("x", factory, midiChannel = 0) }
        assertFailsWith<IllegalArgumentException> { Profile("x", factory, midiChannel = 17) }
    }
}

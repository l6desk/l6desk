package io.github.l6desk.model

import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.sync.MixerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The CH7/8 USB input switches (CC 113/114): unknown until set or reported, never guessed. */
@OptIn(ExperimentalCoroutinesApi::class)
class UsbInputTest {
    private val usb7 = Control.Channel(7, ChannelParam.USB_INPUT)

    @Test
    fun startsUnknownAndIsNeverPushedUntilKnown() = runTest {
        val sim = SimulatorTransport()
        val c = MixerController(sim, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        sim.open(SimulatorTransport.DEVICE)
        assertNull(c.state.value.channel(7).usbInput)
        c.pushAll()
        assertNull(sim.valueOf(usb7), "SEND DESK leaves an unknown switch alone")

        c.setParam(7, ChannelParam.USB_INPUT, 127)
        assertEquals(127, sim.valueOf(usb7))
        sim.moveHardwareControl(Control.Channel(8, ChannelParam.USB_INPUT), 127) // pressed on the mixer
        assertEquals(true, c.state.value.channel(8).usbInput)
        sim.received.clear()
        c.pushAll()
        assertEquals(127, sim.valueOf(Control.Channel(8, ChannelParam.USB_INPUT)), "known now, so pushed")
    }

    @Test
    fun scenesAndResetMixKeepASwitchTheyDontKnow() {
        val desk = MixerState().withChannel(7) { it.with(ChannelParam.USB_INPUT, 127) }
        val scene = SceneBank().store(SceneSlot.A, MixerState())[SceneSlot.A]!!
        assertEquals(true, desk.recall(scene).channel(7).usbInput, "the scene never saw it")
        val sceneWithJacks = MixerState().withChannel(7) { it.with(ChannelParam.USB_INPUT, 0) }
        assertEquals(false, desk.recall(sceneWithJacks).channel(7).usbInput, "the scene knew it")
        assertEquals(true, desk.cleanSlate().channel(7).usbInput, "input setup survives RESET MIX")
        assertNull(MixerState().cleanSlate().channel(7).usbInput)
    }
}

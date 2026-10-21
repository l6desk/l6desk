package io.github.l6desk.midi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import javax.sound.midi.ShortMessage
import javax.sound.midi.SysexMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** The transport against a fake port list (the real Java Sound is exercised against the L6max). */
@OptIn(ExperimentalCoroutinesApi::class)
class JavaSoundMidiTransportTest {
    private class FakePorts(vararg names: String) : MidiPorts {
        var ports = names.map { MidiPorts.Port(it) }
        var failOpen = false
        var failSend = false
        val sent = mutableListOf<List<Int>>()
        var receive: (List<Int>) -> Unit = {}
        var closed = 0

        override fun list() = ports

        override fun open(name: String, onReceive: (List<Int>) -> Unit): MidiPorts.Connection {
            if (failOpen) error("Device busy")
            receive = onReceive
            return object : MidiPorts.Connection {
                override fun send(bytes: List<Int>) {
                    if (failSend) error("Device gone")
                    sent += bytes
                }

                override fun close() {
                    closed++
                }
            }
        }
    }

    private val linux = arrayOf("L6max [hw:4,0,0]", "L6max [hw:4,0,1]", "L6max [hw:4,0,2]")

    private fun kotlinx.coroutines.test.TestScope.transport(ports: MidiPorts) =
        JavaSoundMidiTransport(CoroutineScope(backgroundScope.coroutineContext), pollInterval = 1.hours, system = ports)
            .also { it.refresh() }

    @Test
    fun listsPortsWithL6maxRoles() = runTest {
        val t = transport(FakePorts(*linux, "Midi Through [hw:0,0,0]"))
        val mixer = t.devices.value.single { it.isL6maxMixerControl }
        assertEquals("L6max [hw:4,0,1]", mixer.id)
        assertEquals("L6max Mixer Control Port (hw:4,0,1)", mixer.name)
        assertEquals(4, t.devices.value.size)
    }

    @Test
    fun sendsAndReceives() = runTest {
        val ports = FakePorts(*linux)
        val t = transport(ports)
        val heard = mutableListOf<MidiMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { t.incoming.toList(heard) }

        t.open(t.devices.value.single { it.isL6maxMixerControl })
        assertIs<ConnectionState.Connected>(t.state.value)
        t.send(MidiMessage.ControlChange(1, 106, 127))
        assertEquals(listOf(listOf(0xB0, 106, 127)), ports.sent)

        // Java Sound may hand over several messages at once, with running status.
        ports.receive(listOf(0xB0, 88, 43, 88, 44))
        assertEquals(listOf<MidiMessage>(MidiMessage.ControlChange(1, 88, 43), MidiMessage.ControlChange(1, 88, 44)), heard)
    }

    @Test
    fun unpluggingEndsTheConnectionAndReplugIsListedAgain() = runTest {
        val ports = FakePorts(*linux)
        val t = transport(ports)
        t.open(t.devices.value.single { it.isL6maxMixerControl })
        ports.ports = emptyList()
        t.refresh()
        assertIs<ConnectionState.Error>(t.state.value)
        assertEquals(1, ports.closed)
        assertTrue(t.devices.value.isEmpty())
        ports.ports = linux.map { MidiPorts.Port(it) }
        t.refresh()
        assertEquals(3, t.devices.value.size)
    }

    @Test
    fun openAndSendFailuresBecomeErrors() = runTest {
        val ports = FakePorts(*linux)
        val t = transport(ports)
        val mixer = t.devices.value.single { it.isL6maxMixerControl }
        ports.failOpen = true
        t.open(mixer)
        assertEquals(ConnectionState.Error("Couldn't open ${mixer.name}: Device busy"), t.state.value)
        ports.failOpen = false
        t.open(mixer)
        ports.failSend = true
        t.send(MidiMessage.ControlChange(1, 81, 1))
        assertIs<ConnectionState.Error>(t.state.value)
    }

    @Test
    fun readsTheCcMapFromTheEditorPortOfTheSameMixer() = runTest {
        val custom = io.github.l6desk.model.L6maxLayout.FACTORY_CC_MAP
            .with(io.github.l6desk.model.Control.Channel(1, io.github.l6desk.model.ChannelParam.PAN), 107)
        val opened = mutableListOf<String>()
        val ports = object : MidiPorts {
            override fun list() = listOf("L6max [hw:3,0,2]", "L6max [hw:4,0,1]", "L6max [hw:4,0,2]").map { MidiPorts.Port(it) }
            override fun open(name: String, onReceive: (List<Int>) -> Unit): MidiPorts.Connection {
                opened += name
                return object : MidiPorts.Connection {
                    override fun send(bytes: List<Int>) {
                        if (bytes == L6maxSysEx.EDITOR_OPEN.bytes) onReceive(L6maxSysEx.buildSnapshot(custom).bytes)
                    }
                    override fun close() = Unit
                }
            }
        }
        val t = transport(ports)
        val mixer = t.devices.value.single { it.id == "L6max [hw:4,0,1]" }
        assertEquals(custom, t.readCcMap(mixer))
        assertEquals(listOf("L6max [hw:4,0,2]"), opened, "the Editor port on the same card, not the other L6max")
    }

    @Test
    fun convertsToJavaSoundMessages() {
        val cc = JavaSoundPorts.toJavaMessage(listOf(0xB0, 81, 100))
        assertIs<ShortMessage>(cc)
        assertEquals(listOf(0xB0, 81, 100), cc.message.map { it.toInt() and 0xFF })
        val pc = JavaSoundPorts.toJavaMessage(listOf(0xC0, 2))
        assertEquals(2, (pc as ShortMessage).data1)
        val sysex = JavaSoundPorts.toJavaMessage(L6maxSysEx.EDITOR_OPEN.bytes)
        assertIs<SysexMessage>(sysex)
        assertEquals(L6maxSysEx.EDITOR_OPEN.bytes, sysex.message.map { it.toInt() and 0xFF })
    }
}

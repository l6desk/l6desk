package io.github.l6desk.midi

import io.github.l6desk.model.CcMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage as JMidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.Sequencer
import javax.sound.midi.ShortMessage
import javax.sound.midi.Synthesizer
import javax.sound.midi.SysexMessage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * USB MIDI on the desktop via `javax.sound.midi` (ALSA on Ubuntu). Java Sound lists each port
 * twice (an input and an output with the same name); they are paired into one device here.
 * The device list is polled for hot-plug, and a connected device that disappears ends the
 * connection with an error.
 *
 * On Windows and macOS, Java Sound may not see a newly plugged device until the app restarts.
 */
class JavaSoundMidiTransport(
    private val scope: CoroutineScope,
    private val pollInterval: Duration = 2.seconds,
    private val system: MidiPorts = JavaSoundPorts,
) : MidiTransport, CcMapReader {
    private val _devices = MutableStateFlow<List<MidiDeviceInfo>>(emptyList())
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val _incoming = MutableSharedFlow<MidiMessage>(extraBufferCapacity = 1024)

    override val devices: StateFlow<List<MidiDeviceInfo>> = _devices.asStateFlow()
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()
    override val incoming: SharedFlow<MidiMessage> = _incoming.asSharedFlow()

    private var connection: MidiPorts.Connection? = null

    init {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                refresh()
                delay(pollInterval)
            }
        }
    }

    /** Re-reads the port list now (also called by the poller). */
    fun refresh() {
        val ports = runCatching { system.list() }.getOrElse { emptyList() }
        _devices.value = ports.map { it.toDeviceInfo() }
        val connected = (_state.value as? ConnectionState.Connected)?.device
        if (connected != null && ports.none { it.name == connected.id }) {
            closeConnection()
            _state.value = ConnectionState.Error("${connected.name} was unplugged")
        }
    }

    override suspend fun open(device: MidiDeviceInfo) {
        closeConnection()
        _state.value = ConnectionState.Connecting(device)
        val parser = MidiStreamParser()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                system.open(device.id) { bytes ->
                    // Called on Java Sound's thread. The parser keeps state, so feed it serially.
                    val messages = synchronized(parser) { parser.feed(bytes) }
                    messages.forEach { _incoming.tryEmit(it) }
                }
            }
        }
        result.fold(
            onSuccess = {
                connection = it
                _state.value = ConnectionState.Connected(device)
            },
            onFailure = { _state.value = ConnectionState.Error("Couldn't open ${device.name}: ${it.message ?: it::class.simpleName}") },
        )
    }

    override fun close() {
        closeConnection()
        _state.value = ConnectionState.Disconnected
    }

    override fun send(message: MidiMessage) {
        val c = connection ?: return
        try {
            c.send(message.toBytes())
        } catch (e: Exception) {
            val device = (_state.value as? ConnectionState.Connected)?.device
            closeConnection()
            _state.value = ConnectionState.Error("Lost ${device?.name ?: "the device"}: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * Reads the CC map over the Editor port of the same L6max as [device], read-only: Identity
     * Request, then EditorOpen, then the settings snapshot reply (CLAUDE.md, L6max MIDI facts).
     */
    override suspend fun readCcMap(device: MidiDeviceInfo): CcMap = withContext(Dispatchers.IO) {
        val editors = system.list().map { it.name }.filter { L6maxPorts.portNumber(it) == L6maxPorts.EDITOR }
        // On Linux pick the Editor port on the same ALSA card ("L6max [hw:4,0,1]" -> "[hw:4,0,").
        val sameCard = device.id.substringBeforeLast(",", "").takeIf { it.contains("[hw:") }
        val editor = editors.firstOrNull { sameCard != null && it.startsWith(sameCard) } ?: editors.singleOrNull()
            ?: throw L6maxProtocolException("Can't find the L6max Editor port")
        val reply = CompletableDeferred<MidiMessage>()
        val parser = MidiStreamParser()
        val conn = system.open(editor) { bytes ->
            synchronized(parser) { parser.feed(bytes) }.firstOrNull(L6maxSysEx::isEditorOpenReply)?.let { reply.complete(it) }
        }
        try {
            conn.send(L6maxSysEx.IDENTITY_REQUEST.bytes)
            conn.send(L6maxSysEx.EDITOR_OPEN.bytes)
            val message = withTimeoutOrNull(3.seconds) { reply.await() }
                ?: throw L6maxProtocolException("No reply from the L6max Editor port (is ZOOM L6 Editor running?)")
            L6maxSysEx.parseCcMap(message)
        } finally {
            runCatching { conn.close() }
        }
    }

    private fun closeConnection() {
        connection?.let { runCatching { it.close() } }
        connection = null
    }

    private fun MidiPorts.Port.toDeviceInfo() =
        MidiDeviceInfo(id = name, name = L6maxPorts.displayName(name), kind = MidiDeviceInfo.Kind.USB, l6maxPort = L6maxPorts.portNumber(name))
}

/** The parts of a MIDI system the transport needs; [JavaSoundPorts] is the real one. */
interface MidiPorts {
    /** A port that can both send and receive (Java Sound's input and output paired by name). */
    data class Port(val name: String)

    interface Connection {
        fun send(bytes: List<Int>)
        fun close()
    }

    fun list(): List<Port>

    /** Opens the port called [name]; [onReceive] gets raw bytes from Java Sound's thread. */
    fun open(name: String, onReceive: (List<Int>) -> Unit): Connection
}

/**
 * The name an input and an output share when they're one port. Linux gives both halves the same
 * name; Windows calls them "MIDIIN2 (L6max)" and "MIDIOUT2 (L6max)", both "MIDI2 (L6max)" here.
 */
internal fun pairKey(name: String): String = name.replace(Regex("""(?i)\bmidi(?:in|out)(\d)"""), "MIDI$1")

/**
 * The ports that can both send and receive, from (name, can send to us, can receive from us)
 * devices: an input and an output with the same [pairKey], sorted by name.
 */
internal fun pairPorts(devices: List<Triple<String, Boolean, Boolean>>): List<String> =
    devices.groupBy { pairKey(it.first) }
        .filterValues { halves -> halves.any { it.second } && halves.any { it.third } }
        .keys.sorted()

/** [MidiPorts] backed by `javax.sound.midi`. */
object JavaSoundPorts : MidiPorts {
    private fun hardwareDevices(): List<MidiDevice> = MidiSystem.getMidiDeviceInfo()
        .mapNotNull { runCatching { MidiSystem.getMidiDevice(it) }.getOrNull() }
        .filter { it !is Synthesizer && it !is Sequencer } // Java's software synth and sequencer

    override fun list(): List<MidiPorts.Port> =
        pairPorts(hardwareDevices().map { Triple(it.deviceInfo.name, it.maxTransmitters != 0, it.maxReceivers != 0) }).map { MidiPorts.Port(it) }

    /**
     * Every MIDI device Java Sound sees, as "IN name" / "OUT name" (for problem reports: it's how a
     * port the app doesn't recognise, e.g. on Windows, can be found).
     */
    fun describeAll(): List<String> = runCatching {
        MidiSystem.getMidiDeviceInfo().mapNotNull { info ->
            val d = runCatching { MidiSystem.getMidiDevice(info) }.getOrNull() ?: return@mapNotNull null
            val kind = when {
                d is Synthesizer -> "SYNTH"
                d is Sequencer -> "SEQUENCER"
                d.maxTransmitters != 0 && d.maxReceivers != 0 -> "IN+OUT"
                d.maxTransmitters != 0 -> "IN"
                d.maxReceivers != 0 -> "OUT"
                else -> "?"
            }
            "$kind ${info.name} (${info.vendor}; ${info.description})"
        }
    }.getOrElse { listOf("Java Sound failed: ${it.message}") }

    override fun open(name: String, onReceive: (List<Int>) -> Unit): MidiPorts.Connection {
        val devices = hardwareDevices().filter { pairKey(it.deviceInfo.name) == name }
        val output = devices.first { it.maxReceivers != 0 }
        val input = devices.first { it.maxTransmitters != 0 }
        output.open()
        try {
            input.open()
        } catch (e: Exception) {
            output.close()
            throw e
        }
        val transmitter = input.transmitter
        transmitter.receiver = object : Receiver {
            override fun send(message: JMidiMessage, timeStamp: Long) {
                onReceive(message.message.take(message.length).map { it.toInt() and 0xFF })
            }

            override fun close() = Unit
        }
        val receiver = output.receiver
        return object : MidiPorts.Connection {
            override fun send(bytes: List<Int>) = receiver.send(toJavaMessage(bytes), -1)

            override fun close() {
                runCatching { transmitter.close() }
                runCatching { receiver.close() }
                runCatching { input.close() }
                runCatching { output.close() }
            }
        }
    }

    internal fun toJavaMessage(bytes: List<Int>): JMidiMessage = when {
        bytes.first() == 0xF0 -> SysexMessage(ByteArray(bytes.size) { bytes[it].toByte() }, bytes.size)
        bytes.size == 1 -> ShortMessage(bytes[0])
        bytes.size == 2 -> ShortMessage(bytes[0], bytes[1], 0)
        else -> ShortMessage(bytes[0], bytes[1], bytes[2])
    }
}

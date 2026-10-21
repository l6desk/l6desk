package io.github.l6desk

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import io.github.l6desk.midi.CcMapReader
import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.L6maxPorts
import io.github.l6desk.midi.L6maxProtocolException
import io.github.l6desk.midi.L6maxSysEx
import io.github.l6desk.midi.MidiDeviceInfo
import io.github.l6desk.midi.MidiMessage
import io.github.l6desk.midi.MidiStreamParser
import io.github.l6desk.midi.MidiTransport
import io.github.l6desk.model.CcMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import android.media.midi.MidiDeviceInfo as AndroidDeviceInfo

/**
 * USB MIDI on Android (milestone 7, USB-OTG): the L6max plugged straight into the tablet, through
 * Android's MIDI service (no USB permission prompt needed). Each USB MIDI port is a device; the
 * L6max's three (verified on the Galaxy Tab A11: "ZOOM CORPORATION L6max", 3 in, 3 out) are named
 * as on the desktop, in the same order as its ALSA sub-devices, so port 2 is Mixer Control.
 * Plugging and unplugging are reported by Android, so nothing is polled. Bluetooth MIDI is deferred
 * (no adapter yet).
 */
class AndroidMidiTransport(context: Context) : MidiTransport, CcMapReader {
    private val manager: MidiManager? = context.getSystemService(MidiManager::class.java)
    private val thread = HandlerThread("L6Desk MIDI").apply { start() }
    private val handler = Handler(thread.looper)

    private val _devices = MutableStateFlow<List<MidiDeviceInfo>>(emptyList())
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val _incoming = MutableSharedFlow<MidiMessage>(extraBufferCapacity = 1024)

    override val devices: StateFlow<List<MidiDeviceInfo>> = _devices.asStateFlow()
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()
    override val incoming: SharedFlow<MidiMessage> = _incoming.asSharedFlow()

    /** The open port: the Android device, its port number, and the two directions. */
    private class Open(val info: AndroidDeviceInfo, val device: MidiDevice, val input: MidiInputPort?, val output: MidiOutputPort?, val listed: MidiDeviceInfo) {
        fun close() {
            runCatching { output?.close() }
            runCatching { input?.close() }
            runCatching { device.close() }
        }
    }

    @Volatile
    private var open: Open? = null

    init {
        manager?.registerDeviceCallback(
            object : MidiManager.DeviceCallback() {
                override fun onDeviceAdded(device: AndroidDeviceInfo) = refresh()
                override fun onDeviceRemoved(device: AndroidDeviceInfo) {
                    val o = open
                    if (o != null && o.info.id == device.id) {
                        open = null
                        o.close()
                        _state.value = ConnectionState.Error("Lost ${o.listed.name}: unplugged")
                    }
                    refresh()
                }
            },
            handler,
        )
        refresh()
    }

    @Suppress("DEPRECATION")
    private fun usbDevices(): List<AndroidDeviceInfo> {
        val m = manager ?: return emptyList()
        val all = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
        } else {
            m.devices.toList()
        }
        return all.filter { it.type == AndroidDeviceInfo.TYPE_USB }
    }

    private fun refresh() {
        _devices.value = usbDevices().flatMap { info -> ports(info) }
    }

    private fun productOf(info: AndroidDeviceInfo): String {
        val p = info.properties
        return p.getString(AndroidDeviceInfo.PROPERTY_NAME)
            ?: listOfNotNull(p.getString(AndroidDeviceInfo.PROPERTY_MANUFACTURER), p.getString(AndroidDeviceInfo.PROPERTY_PRODUCT)).joinToString(" ").ifBlank { "USB MIDI device" }
    }

    /** The ports of [info] that go both ways, as devices. Ids don't change on a replug (Android's own id does). */
    private fun ports(info: AndroidDeviceInfo): List<MidiDeviceInfo> {
        val product = productOf(info)
        val isL6max = L6maxPorts.portNumber(product) != null || "l6max" in product.lowercase().replace(" ", "")
        val count = minOf(info.inputPortCount, info.outputPortCount)
        return (0 until count).map { i ->
            val l6maxPort = if (isL6max && count == 3) i + 1 else null
            val portName = info.ports.firstOrNull { it.portNumber == i && !it.name.isNullOrBlank() }?.name
            val name = when {
                l6maxPort != null -> "L6max ${L6maxPorts.NAMES.getValue(l6maxPort)} (USB)"
                portName != null -> "$product: $portName"
                count > 1 -> "$product, port ${i + 1}"
                else -> product
            }
            MidiDeviceInfo("$ID_PREFIX$product:$i", name, MidiDeviceInfo.Kind.USB, l6maxPort)
        }
    }

    private fun find(device: MidiDeviceInfo): Pair<AndroidDeviceInfo, Int>? {
        val rest = device.id.removePrefix(ID_PREFIX)
        val product = rest.substringBeforeLast(':')
        val port = rest.substringAfterLast(':').toIntOrNull() ?: return null
        val info = usbDevices().firstOrNull { productOf(it) == product } ?: return null
        return info to port
    }

    private suspend fun openDevice(info: AndroidDeviceInfo): MidiDevice? {
        val m = manager ?: return null
        return suspendCancellableCoroutine { cont -> m.openDevice(info, { d -> cont.resume(d) }, handler) }
    }

    override suspend fun open(device: MidiDeviceInfo) {
        close()
        _state.value = ConnectionState.Connecting(device)
        val (info, port) = find(device) ?: run {
            _state.value = ConnectionState.Error("${device.name} isn't connected")
            return
        }
        val d = openDevice(info) ?: run {
            _state.value = ConnectionState.Error("Couldn't open ${device.name}")
            return
        }
        val input = d.openInputPort(port)
        val output = d.openOutputPort(port)
        if (input == null || output == null) {
            runCatching { input?.close() }
            runCatching { output?.close() }
            runCatching { d.close() }
            _state.value = ConnectionState.Error("${device.name} is in use by another app")
            return
        }
        val parser = MidiStreamParser()
        output.connect(
            object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    val bytes = ByteArray(count).also { msg.copyInto(it, 0, offset, offset + count) }
                    synchronized(parser) { parser.feed(bytes) }.forEach { _incoming.tryEmit(it) }
                }
            },
        )
        open = Open(info, d, input, output, device)
        _state.value = ConnectionState.Connected(device)
    }

    override fun close() {
        open?.close()
        open = null
        _state.value = ConnectionState.Disconnected
    }

    override fun send(message: MidiMessage) {
        val o = open ?: return
        val bytes = message.toBytes()
        try {
            o.input?.send(ByteArray(bytes.size) { bytes[it].toByte() }, 0, bytes.size)
        } catch (e: Exception) {
            open = null
            o.close()
            _state.value = ConnectionState.Error("Lost ${o.listed.name}: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * Reads the CC map over the Editor port (port 3) of the same L6max, read-only: Identity
     * Request, then EditorOpen, then the settings snapshot reply (CLAUDE.md, L6max MIDI facts).
     */
    override suspend fun readCcMap(device: MidiDeviceInfo): CcMap {
        val o = open
        val (info, _) = find(device) ?: throw L6maxProtocolException("The L6max isn't connected")
        val editorPort = L6maxPorts.EDITOR - 1
        // The device is already open for mixing; Android allows a second port on the same device.
        val d = if (o != null && o.info.id == info.id) o.device else openDevice(info) ?: throw L6maxProtocolException("Couldn't open the L6max")
        val input = d.openInputPort(editorPort)
        val output = d.openOutputPort(editorPort)
        try {
            if (input == null || output == null) throw L6maxProtocolException("The L6max Editor port is in use (is ZOOM L6 Editor running?)")
            val reply = CompletableDeferred<MidiMessage>()
            val parser = MidiStreamParser()
            output.connect(
                object : MidiReceiver() {
                    override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        val bytes = ByteArray(count).also { msg.copyInto(it, 0, offset, offset + count) }
                        synchronized(parser) { parser.feed(bytes) }.firstOrNull(L6maxSysEx::isEditorOpenReply)?.let { reply.complete(it) }
                    }
                },
            )
            for (m in listOf(L6maxSysEx.IDENTITY_REQUEST, L6maxSysEx.EDITOR_OPEN)) {
                val b = m.toBytes()
                input.send(ByteArray(b.size) { b[it].toByte() }, 0, b.size)
            }
            val message = withTimeoutOrNull(3.seconds) { reply.await() }
                ?: throw L6maxProtocolException("No reply from the L6max Editor port (is ZOOM L6 Editor running?)")
            return L6maxSysEx.parseCcMap(message)
        } finally {
            runCatching { output?.close() }
            runCatching { input?.close() }
            if (d !== o?.device) runCatching { d.close() }
        }
    }

    companion object {
        const val ID_PREFIX = "android-usb:"
    }
}

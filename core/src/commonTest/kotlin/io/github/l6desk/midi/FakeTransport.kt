package io.github.l6desk.midi

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** A transport whose devices and behaviour tests control directly. */
class FakeTransport(vararg initial: MidiDeviceInfo, var failOpen: Boolean = false) : MidiTransport {
    override val devices = MutableStateFlow(initial.toList())
    override val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val incoming = MutableSharedFlow<MidiMessage>(extraBufferCapacity = 64)
    val sent = mutableListOf<MidiMessage>()
    val opened = mutableListOf<MidiDeviceInfo>()

    override suspend fun open(device: MidiDeviceInfo) {
        opened += device
        state.value = if (failOpen) ConnectionState.Error("can't open") else ConnectionState.Connected(device)
    }

    override fun close() {
        state.value = ConnectionState.Disconnected
    }

    override fun send(message: MidiMessage) {
        sent += message
    }

    companion object {
        fun usb(name: String) = MidiDeviceInfo(name, L6maxPorts.displayName(name), MidiDeviceInfo.Kind.USB, L6maxPorts.portNumber(name))
    }
}

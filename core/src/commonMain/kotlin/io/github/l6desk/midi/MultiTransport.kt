package io.github.l6desk.midi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/**
 * Presents several transports (USB, simulator, later Bluetooth and the bridge) as one, so
 * [io.github.l6desk.sync.MixerController] only ever deals with a single transport. Opening a
 * device closes whichever transport was active before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiTransport(private val transports: List<MidiTransport>, scope: CoroutineScope) : MidiTransport, CcMapReader {
    private val active = MutableStateFlow<MidiTransport?>(null)

    override val devices: StateFlow<List<MidiDeviceInfo>> =
        combine(transports.map { it.devices }) { lists -> lists.flatMap { it } }
            .stateIn(scope, SharingStarted.Eagerly, transports.flatMap { it.devices.value })

    override val state: StateFlow<ConnectionState> =
        active.flatMapLatest { it?.state ?: flowOf(ConnectionState.Disconnected) }
            .stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    override val incoming: SharedFlow<MidiMessage> =
        active.flatMapLatest { it?.incoming ?: emptyFlow() }
            .shareIn(scope, SharingStarted.Eagerly)

    override suspend fun open(device: MidiDeviceInfo) {
        val owner = requireNotNull(transports.firstOrNull { t -> t.devices.value.any { it.id == device.id } }) {
            "No transport offers ${device.name}"
        }
        active.value?.takeIf { it !== owner }?.close()
        active.value = owner
        owner.open(device)
    }

    override fun close() {
        active.value?.close()
    }

    override fun send(message: MidiMessage) {
        active.value?.send(message)
    }

    override suspend fun readCcMap(device: MidiDeviceInfo): io.github.l6desk.model.CcMap {
        val reader = active.value as? CcMapReader ?: throw UnsupportedOperationException("This connection can't read the mixer's map")
        return reader.readCcMap(device)
    }
}

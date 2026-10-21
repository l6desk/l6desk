package io.github.l6desk.net

import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.MidiDeviceInfo
import io.github.l6desk.midi.MidiMessage
import io.github.l6desk.midi.MidiStreamParser
import io.github.l6desk.midi.MidiTransport
import io.github.l6desk.padcard.PadCardJson
import io.github.l6desk.padcard.PadCardState
import io.github.l6desk.padcard.PadCommand
import io.github.l6desk.padcard.PadResult
import io.github.l6desk.player.PlayerCommand
import io.github.l6desk.recordings.DownloadProgress
import io.github.l6desk.recordings.RecCommand
import io.github.l6desk.recordings.RecStage
import io.github.l6desk.recordings.RecState
import io.github.l6desk.recordings.Recording
import io.github.l6desk.recordings.RecordingOptions
import io.github.l6desk.recordings.RecordingSaver
import io.github.l6desk.recordings.RecordingsJson
import io.github.l6desk.recordings.SavedFile
import io.github.l6desk.player.PlayerJson
import io.github.l6desk.player.PlayerState
import io.github.l6desk.player.Track
import io.github.l6desk.player.UploadSource
import io.github.l6desk.player.UploadStage
import io.github.l6desk.player.UploadStart
import io.github.l6desk.player.UploadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A bridge the user has entered: host name or IP address, and port. */
data class BridgeAddress(val host: String, val port: Int = BridgeProtocol.DEFAULT_PORT) {
    val id: String get() = "bridge:$host:$port"
    override fun toString() = if (port == BridgeProtocol.DEFAULT_PORT) host else "$host:$port"

    companion object {
        /** Parses "192.168.1.20" or "192.168.1.20:5705"; null if it isn't usable. */
        fun parse(text: String): BridgeAddress? {
            val t = text.trim()
            if (t.isEmpty() || t.any { it.isWhitespace() }) return null
            val host = t.substringBeforeLast(':', t)
            val port = if (':' in t) t.substringAfterLast(':').toIntOrNull() ?: return null else BridgeProtocol.DEFAULT_PORT
            return if (host.isNotEmpty() && port in 1..65535) BridgeAddress(host, port) else null
        }
    }
}

class BridgeBusyException(message: String) : Exception(message)

/**
 * The music player at the connected bridge: its tracks (all playlists) and what it's doing.
 * [canUpload]: the bridge takes uploads and has playlists (protocol v3).
 */
/** A bridge asking for its PIN (protocol v6): which one, and whether the last PIN tried was wrong. */
data class PinRequest(val device: MidiDeviceInfo, val wrong: Boolean = false)

private class PinNeededException : Exception("The bridge asks for a PIN")

/** The bridge the app is connected to, as it described itself (for problem reports). */
data class BridgeSession(val name: String, val address: BridgeAddress, val protocol: Int, val mixer: MixerStatus)

data class RemotePlayer(val tracks: List<Track> = emptyList(), val state: PlayerState = PlayerState(), val canUpload: Boolean = false) {
    /** The songs in the playlist shown. */
    val shown: List<Track> get() = state.playlist?.let { p -> tracks.filter { it.playlist == p } } ?: tracks
}

/**
 * Client side of the Wi-Fi bridge (CLAUDE.md, route C). Each entered bridge is a device, and so is
 * each bridge [discovery] finds on the network (named, not addressed: its address is looked up on
 * every connection attempt, so it stays the same device when its IP changes). Once opened, it
 * keeps the link up: if it drops it reports "link lost" (within [timeout]) and reconnects every
 * [retryDelay] until [close] is called.
 */
class NetworkMidiTransport(
    private val scope: CoroutineScope,
    private val connector: SocketConnector,
    private val clientName: String,
    private val keepalive: Duration = 2.seconds,
    private val timeout: Duration = 5.seconds,
    private val retryDelay: Duration = 2.seconds,
    private val discovery: BridgeDiscovery? = null,
) : MidiTransport, io.github.l6desk.player.PlayerHost {
    private val _devices = MutableStateFlow<List<MidiDeviceInfo>>(emptyList())
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val _incoming = MutableSharedFlow<MidiMessage>(extraBufferCapacity = 1024)

    override val devices: StateFlow<List<MidiDeviceInfo>> = _devices.asStateFlow()
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()
    override val incoming: SharedFlow<MidiMessage> = _incoming.asSharedFlow()

    private val _player = MutableStateFlow<RemotePlayer?>(null)

    /** The connected bridge's music player, or null (not connected, or the bridge has none). */
    override val player: StateFlow<RemotePlayer?> = _player.asStateFlow()

    private val _canPowerOff = MutableStateFlow(false)

    /** The connected bridge can power its computer off when asked ([powerOffBridge]). */
    val canPowerOff: StateFlow<Boolean> = _canPowerOff.asStateFlow()
    private val powerOffAcknowledged = MutableStateFlow(false)

    private val _session = MutableStateFlow<BridgeSession?>(null)

    /** The connected bridge's name, address, protocol version and mixer, or null when not connected. */
    val bridgeSession: StateFlow<BridgeSession?> = _session.asStateFlow()

    private val _info = MutableStateFlow<BridgeInfo?>(null)
    private val infoReceived = MutableStateFlow(0)

    /** What the connected bridge says about itself and its computer (v5 bridges), or null. */
    val bridgeInfo: StateFlow<BridgeInfo?> = _info.asStateFlow()

    private val _padCardOffered = MutableStateFlow(false)
    private val _padCard = MutableStateFlow<PadCardState?>(null)

    /** The connected bridge offers PAD SOUNDS (v7: the Pi, set up to mount the L6max's card). */
    val padCardOffered: StateFlow<Boolean> = _padCardOffered.asStateFlow()

    /** The L6max's card at the bridge, while it's mounted there (file transfer mode), else null. */
    val padCard: StateFlow<PadCardState?> = _padCard.asStateFlow()
    private val padResults = MutableSharedFlow<PadResult>(extraBufferCapacity = 16)
    private val padIds = MutableStateFlow(0)

    private val _recordingsOffered = MutableStateFlow(false)
    private val _recordings = MutableStateFlow<List<Recording>?>(null)

    /** The connected bridge offers RECORDINGS (v8: the Pi, set up to mount the L6max's card). */
    val recordingsOffered: StateFlow<Boolean> = _recordingsOffered.asStateFlow()

    /** The recordings on the card at the bridge, newest first, while it's mounted there; else null. */
    val recordings: StateFlow<List<Recording>?> = _recordings.asStateFlow()

    /** The download in progress: its id, and where its frames go. */
    private class Download(val id: Int) {
        val states = kotlinx.coroutines.channels.Channel<RecState>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val data = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    }
    private var download: Download? = null
    private val downloadIds = MutableStateFlow(0)

    private var job: Job? = null
    private var session: FrameSession? = null
    private var version = 0

    /** Uploads in progress, by id: their latest status from the bridge. */
    private val uploads = MutableStateFlow<Map<Int, MutableStateFlow<UploadStatus?>>>(emptyMap())
    private val uploadIds = MutableStateFlow(0)

    private var entered: List<BridgeAddress> = emptyList()
    private var found: List<DiscoveredBridge> = emptyList()
    private var own: Set<BridgeAddress> = emptySet()

    /** PINs by device id, as entered by the user (and saved by the app). */
    private val pins = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _pinRequest = MutableStateFlow<PinRequest?>(null)

    /** A bridge waiting for its PIN; connecting carries on once [setPin] gives one. */
    val pinRequest: StateFlow<PinRequest?> = _pinRequest.asStateFlow()

    /** The PINs to answer bridges with, by device id (the app's saved ones, at start). */
    fun setPins(byDevice: Map<String, String>) {
        pins.value = byDevice
    }

    /** The PIN for [deviceId], e.g. just typed in: connecting to it carries on. */
    fun setPin(deviceId: String, pin: String) {
        pins.update { it + (deviceId to pin) }
    }

    init {
        if (discovery != null) {
            discovery.start()
            scope.launch {
                discovery.found.collect {
                    found = it
                    publishDevices()
                }
            }
        }
    }

    /** The bridges entered by the user, offered as devices (with any found on the network). */
    fun setBridges(bridges: List<BridgeAddress>) {
        entered = bridges.distinct()
        publishDevices()
    }

    /**
     * This computer's own bridge (the desktop's Bridge mode), at these addresses: not offered, so
     * the app never connects to itself. Empty when Bridge mode is off.
     */
    fun setOwnBridge(addresses: Set<BridgeAddress>) {
        own = addresses
        publishDevices()
    }

    private fun publishDevices() {
        // A found bridge that was also entered by hand is listed once, as entered (so a remembered
        // choice still matches), but named and marked as found.
        val manual = entered.map { address ->
            val match = found.firstOrNull { it.address == address }
            MidiDeviceInfo(address.id, match?.let { "${it.name} ($address)" } ?: "Bridge $address", MidiDeviceInfo.Kind.NETWORK, found = match != null)
        }
        val discovered = found.filter { f -> entered.none { it == f.address } && f.address !in own }
            .map { MidiDeviceInfo(FOUND_PREFIX + it.name, "${it.name} (${it.address})", MidiDeviceInfo.Kind.NETWORK, found = true) }
        _devices.value = manual + discovered
    }

    override suspend fun open(device: MidiDeviceInfo) {
        close()
        val resolve: () -> BridgeAddress? = if (device.id.startsWith(FOUND_PREFIX)) {
            val name = device.id.removePrefix(FOUND_PREFIX);
            { found.firstOrNull { it.name == name }?.address }
        } else {
            val address = requireNotNull(BridgeAddress.parse(device.id.removePrefix("bridge:"))) { "Not a bridge: ${device.id}" };
            { address }
        }
        job = scope.launch { keepConnected(device, resolve) }
    }

    override fun close() {
        job?.cancel()
        job = null
        session = null
        failUploads()
        _player.value = null
        _canPowerOff.value = false
        _session.value = null
        _info.value = null
        _pinRequest.value = null
        clearPadCard()
        _state.value = ConnectionState.Disconnected
    }

    private fun clearPadCard() {
        _padCardOffered.value = false
        _padCard.value = null
        _recordingsOffered.value = false
        _recordings.value = null
        download?.let { d ->
            d.states.close(IllegalStateException("Link to the bridge lost"))
            d.data.close()
        }
    }

    /** What a download through the bridge saved, and how many samples each file had clipped (24-bit). */
    data class DownloadResult(val files: List<String>, val bytes: Long, val clipped: Map<String, Long>, val skipped: List<String> = emptyList())

    /**
     * Downloads [recording] from the card at the bridge, as [options] say, into [saver]: the bridge
     * reads and converts the files and sends them, behind MIDI; each is finished once complete, and
     * abandoned if the download fails or is cancelled. Throws with a message for the user on failure.
     */
    suspend fun downloadRecording(recording: Recording, options: RecordingOptions, saver: RecordingSaver, onProgress: (DownloadProgress) -> Unit): DownloadResult {
        val s = session
        if (s == null || _state.value !is ConnectionState.Connected || !_recordingsOffered.value) error("Not connected to a Pi that has the L6max's card")
        val d = Download(downloadIds.updateAndGet { it + 1 })
        download = d
        var current: SavedFile? = null
        var finished = false
        try {
            s.send(Frame.RecCommand(RecordingsJson.encodeCommand(RecCommand.Start(d.id, recording.folder, options))))
            // Preparing (normalising reads the files first), then the list of files that follow.
            // The bridge reports at least every half second while it works: silence means it's stuck.
            suspend fun nextState() = withTimeoutOrNull(DOWNLOAD_SILENCE) { d.states.receive() } ?: error("The Pi stopped sending the recording")
            var state = nextState()
            while (state.stage == RecStage.PREPARING) {
                if (state.total > 0) onProgress(DownloadProgress("The Pi is reading the files to normalise them", state.done, state.total))
                state = nextState()
            }
            if (state.stage == RecStage.FAILED) error(state.message ?: "The Pi couldn't send the recording")
            check(state.stage == RecStage.SENDING) { "The Pi didn't send the recording" }
            val files = state.files
            val total = files.sumOf { it.bytes }
            var received = 0L
            var acked = 0L
            // The bridge's DONE goes ahead of its last data (states aren't queued behind bulk data):
            // kept until the data is all in. A FAILED stops the download at once.
            var ended: RecState? = null
            suspend fun nextChunk(): ByteArray {
                // Data already waiting is taken without suspending, so check here that CANCEL wasn't pressed.
                currentCoroutineContext().ensureActive()
                while (true) {
                    val got = withTimeoutOrNull(DOWNLOAD_SILENCE) {
                        kotlinx.coroutines.selects.select<Any?> {
                            d.data.onReceiveCatching { it.getOrNull() }
                            if (ended == null) d.states.onReceiveCatching { it.getOrNull() }
                        }
                    } ?: error(if (_state.value !is ConnectionState.Connected) "Link to the bridge lost" else "The Pi stopped sending the recording")
                    when (got) {
                        is ByteArray -> return got
                        is RecState -> if (got.stage == RecStage.FAILED) error(got.message ?: "The Pi couldn't send the recording") else ended = got
                    }
                }
            }
            var pending: ByteArray? = null // the part of a frame that belongs to the next file
            val skipped = mutableListOf<String>()
            for (f in files) {
                val out = saver.create(recording.folder, f.name, f.bytes)
                if (out == null) skipped += f.name
                current = out
                var left = f.bytes
                while (left > 0) {
                    val chunk = pending ?: nextChunk()
                    pending = null
                    val n = minOf(left, chunk.size.toLong()).toInt()
                    out?.write(chunk, 0, n)
                    left -= n
                    received += n
                    if (n < chunk.size) pending = chunk.copyOfRange(n, chunk.size)
                    if (received - acked >= ACK_EVERY || received == total) {
                        s.send(Frame.RecCommand(RecordingsJson.encodeCommand(RecCommand.Ack(d.id, received))))
                        acked = received
                    }
                    onProgress(DownloadProgress(f.name, received, total))
                }
                out?.finish()
                current = null
            }
            val end = ended ?: nextState()
            if (end.stage == RecStage.FAILED) error(end.message ?: "The Pi couldn't send the recording")
            finished = true
            return DownloadResult(files.map { it.name } - skipped.toSet(), files.filter { it.name !in skipped }.sumOf { it.bytes }, end.clipped, skipped)
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            throw IllegalStateException("Link to the bridge lost")
        } finally {
            current?.abort()
            if (download === d) download = null
            if (!finished && session === s) s.send(Frame.RecCommand(RecordingsJson.encodeCommand(RecCommand.Cancel(d.id))))
        }
    }

    /**
     * Sends a change to the card at the bridge ([make] is given the command's id) and waits up to
     * [timeout] for the bridge's answer (copying a long song to the card takes a while).
     */
    suspend fun padCommand(timeout: Duration = 120.seconds, make: (Int) -> PadCommand): PadResult {
        val s = session
        if (s == null || _state.value !is ConnectionState.Connected || !_padCardOffered.value) {
            return PadResult(0, "Not connected to a Pi that can change the pads", error = true)
        }
        val command = make(padIds.updateAndGet { it + 1 })
        val lost = PadResult(command.id, "Link to the bridge lost", error = true)
        return withTimeoutOrNull(timeout) {
            merge(
                padResults.onSubscription { s.send(Frame.PadCommand(PadCardJson.encodeCommand(command))) }.filter { it.id == command.id },
                _state.filter { it !is ConnectionState.Connected }.map { lost },
            ).first()
        } ?: PadResult(command.id, "The Pi didn't answer", error = true)
    }

    /**
     * Asks the connected bridge to power its computer off. Returns true once the bridge has
     * acknowledged it (it then shuts down, so the link drops), false if it can't or didn't answer
     * within [timeout].
     */
    suspend fun powerOffBridge(timeout: Duration = 3.seconds): Boolean {
        val s = session
        if (s == null || _state.value !is ConnectionState.Connected || !_canPowerOff.value) return false
        powerOffAcknowledged.value = false
        s.send(Frame.PowerOff)
        return withTimeoutOrNull(timeout) { powerOffAcknowledged.first { it } } ?: false
    }

    /** Sends [command] to the bridge's music player (ignored while not connected). */
    /**
     * Asks the bridge for fresh [bridgeInfo] (e.g. for a problem report) and waits up to [timeout]
     * for it. Returns what's known then: the old info if the bridge didn't answer, null if it's
     * older than v5 or not connected.
     */
    suspend fun refreshBridgeInfo(timeout: Duration = 1.5.seconds): BridgeInfo? {
        val s = session
        if (s == null || _state.value !is ConnectionState.Connected || version < BridgeProtocol.BRIDGE_INFO_VERSION) return _info.value
        val before = infoReceived.value
        s.send(Frame.BridgeInfoRequest)
        withTimeoutOrNull(timeout) { infoReceived.first { it != before } }
        return _info.value
    }

    override val noAudioHint = "The L6max's USB audio isn't available at the bridge: is the L6max connected to the Pi?"

    override fun command(command: PlayerCommand) = playerCommand(command)

    fun playerCommand(command: PlayerCommand) {
        if (_state.value !is ConnectionState.Connected || _player.value == null) return
        session?.send(Frame.Command(PlayerJson.encodeCommand(command)))
    }

    /**
     * Sends [source] to the bridge, to be added to the end of [playlist]; returns how it ended
     * (DONE or FAILED). [onStatus] sees the bridge's reports as they come (bytes received,
     * converting). Only a little data is ever unacknowledged, and MIDI goes ahead of it.
     */
    override suspend fun upload(source: UploadSource, playlist: String, onStatus: (UploadStatus) -> Unit): UploadStatus {
        val s = session
        if (s == null || _state.value !is ConnectionState.Connected || version < BridgeProtocol.UPLOAD_VERSION) {
            return UploadStatus(0, UploadStage.FAILED, message = "Not connected to a bridge that takes uploads")
        }
        val id = uploadIds.updateAndGet { it + 1 }
        val status = MutableStateFlow<UploadStatus?>(null)
        uploads.update { it + (id to status) }
        var finished = false
        return try {
            coroutineScope {
                val reporter = launch { status.collect { it?.let(onStatus) } }
                s.send(Frame.UploadStart(PlayerJson.encodeUploadStart(UploadStart(id, playlist, source.name, source.size))))
                val buffer = ByteArray(UPLOAD_CHUNK)
                var sent = 0L
                var result: UploadStatus? = null
                while (result == null) {
                    // Wait until little enough is unacknowledged (or the bridge gave up).
                    val st = status.first { it?.stage == UploadStage.FAILED || (it?.received ?: 0L) >= sent - UPLOAD_WINDOW }
                    if (st?.stage == UploadStage.FAILED) {
                        result = st
                        break
                    }
                    val n = source.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    s.sendBulk(Frame.UploadData(id, buffer.copyOf(n)))
                    sent += n
                }
                if (result == null) {
                    s.sendBulk(Frame.UploadData(id, ByteArray(0)))
                    result = status.filterNotNull().first { it.stage == UploadStage.DONE || it.stage == UploadStage.FAILED }
                }
                finished = true
                reporter.cancel()
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finished = true
            UploadStatus(id, UploadStage.FAILED, message = "Link to the bridge lost")
        } finally {
            uploads.update { it - id }
            if (!finished && session === s) s.send(Frame.UploadCancel(id))
        }
    }

    override fun send(message: MidiMessage) {
        if (_state.value !is ConnectionState.Connected) return
        val bytes = message.toBytes()
        session?.send(Frame.Midi(ByteArray(bytes.size) { bytes[it].toByte() }))
    }

    private suspend fun keepConnected(device: MidiDeviceInfo, resolve: () -> BridgeAddress?) {
        var wasConnected = false
        // Lowered to what an older bridge says it speaks (it refuses newer versions), then retried.
        var helloVersion = BridgeProtocol.VERSION
        while (scope.isActive) {
            val address = resolve()
            if (address == null) {
                _state.value = ConnectionState.Error("${device.id.removePrefix(FOUND_PREFIX)} isn't on the network — waiting for it…")
                delay(retryDelay)
                continue
            }
            var bridgeName = address.toString()
            if (!wasConnected) _state.value = ConnectionState.Connecting(device)
            try {
                val socket = connector.connect(address.host, address.port, 3.seconds)
                val s = FrameSession(socket, keepalive, timeout)
                session = s
                val parser = MidiStreamParser()
                s.send(Frame.Hello(helloVersion, clientName))
                s.run { frame ->
                    when (frame) {
                        is Frame.Welcome -> {
                            if (frame.version !in BridgeProtocol.MIN_VERSION..BridgeProtocol.VERSION) {
                                throw BridgeProtocolException("The bridge speaks protocol v${frame.version}, this app v${BridgeProtocol.VERSION}")
                            }
                            wasConnected = true
                            version = frame.version
                            _canPowerOff.value = false // until the bridge says it can (v4, right after this)
                            clearPadCard() // until the bridge describes its card (v7)
                            _info.value = null // until the bridge describes itself (v5)
                            bridgeName = frame.bridgeName
                            _session.value = BridgeSession(bridgeName, address, frame.version, frame.mixer)
                            _state.value = ConnectionState.Connected(device.copy(name = describe(address, bridgeName, frame.mixer)))
                        }
                        is Frame.Status -> if (_state.value is ConnectionState.Connected) {
                            _session.value = _session.value?.copy(mixer = frame.mixer)
                            _state.value = ConnectionState.Connected(device.copy(name = describe(address, bridgeName, frame.mixer)))
                        }
                        is Frame.Midi -> parser.feed(frame.bytes).forEach { _incoming.tryEmit(it) }
                        is Frame.Busy -> throw BridgeBusyException(frame.reason)
                        is Frame.PinRequired -> {
                            val pin = pins.value[device.id] ?: throw PinNeededException()
                            s.send(Frame.PinAnswer(BridgePin.answer(pin, frame.nonce)))
                        }
                        is Frame.Tracks -> runCatching { PlayerJson.decodeTracks(frame.json) }.getOrNull()?.let { tracks ->
                            _player.value = remotePlayer().copy(tracks = tracks)
                        }
                        is Frame.Playback -> runCatching { PlayerJson.decodeState(frame.json) }.getOrNull()?.let { state ->
                            _player.value = remotePlayer().copy(state = state)
                        }
                        Frame.CanPowerOff -> _canPowerOff.value = true
                        Frame.PowerOff -> powerOffAcknowledged.value = true
                        is Frame.BridgeInfo -> runCatching { BridgeInfo.decode(frame.json) }.getOrNull()?.let { info ->
                            _info.value = info
                            infoReceived.update { it + 1 }
                        }
                        is Frame.PadCard -> runCatching { PadCardJson.decodeState(frame.json) }.onSuccess { card ->
                            _padCard.value = card
                            _padCardOffered.value = true
                        }
                        is Frame.Recordings -> runCatching { RecordingsJson.decodeList(frame.json) }.onSuccess { list ->
                            _recordings.value = list
                            _recordingsOffered.value = true
                        }
                        is Frame.RecState -> runCatching { RecordingsJson.decodeState(frame.json) }.getOrNull()?.let { st ->
                            download?.takeIf { it.id == st.id }?.states?.trySend(st)
                        }
                        is Frame.RecData -> download?.takeIf { it.id == frame.id }?.data?.trySend(frame.bytes)
                        is Frame.PadResult -> runCatching { PadCardJson.decodeResult(frame.json) }.getOrNull()?.let { padResults.tryEmit(it) }
                        is Frame.UploadState -> runCatching { PlayerJson.decodeUploadStatus(frame.json) }.getOrNull()?.let { st ->
                            uploads.value[st.id]?.value = st
                        }
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A PIN to ask for (none saved, or the saved one was wrong): wait for the user's.
                val wrongPin = e is BridgeBusyException && e.message == BridgeProtocol.WRONG_PIN
                if (e is PinNeededException || wrongPin) {
                    session = null
                    if (wrongPin) pins.update { it - device.id }
                    _state.value = ConnectionState.Error(if (wrongPin) "Wrong PIN for ${device.name}" else "${device.name} asks for its PIN")
                    _pinRequest.value = PinRequest(device, wrong = wrongPin)
                    pins.first { it[device.id] != null }
                    _pinRequest.value = null
                    if (!wasConnected) _state.value = ConnectionState.Connecting(device)
                    continue
                }
                val older = (e as? BridgeBusyException)?.message?.let { OLDER_BRIDGE.find(it) }?.groupValues?.get(1)?.toIntOrNull()
                if (older != null && older < helloVersion && older >= BridgeProtocol.MIN_VERSION) {
                    helloVersion = older
                    session = null
                    continue // straight away, in the version it speaks
                }
                session = null
                _player.value = null
                _canPowerOff.value = false
                _session.value = null
                _info.value = null
                clearPadCard()
                failUploads()
                _state.value = ConnectionState.Error(
                    when {
                        e is BridgeBusyException && "isn't supported" in (e.message ?: "") ->
                            "Bridge $address needs updating to this app's version (${e.message})"
                        e is BridgeBusyException -> "Bridge $address is busy: ${e.message}"
                        // Why (nothing heard, or closed by the bridge) goes in the app's log and reports.
                        wasConnected -> "Link to bridge $address lost (${e.message ?: e::class.simpleName}) — reconnecting…"
                        else -> "Can't reach bridge $address: ${e.message ?: e::class.simpleName}"
                    },
                )
            }
            delay(retryDelay)
        }
    }

    private fun remotePlayer() = (_player.value ?: RemotePlayer()).copy(canUpload = version >= BridgeProtocol.UPLOAD_VERSION)

    private fun failUploads() {
        for ((id, status) in uploads.value) status.value = UploadStatus(id, UploadStage.FAILED, message = "Link to the bridge lost")
    }

    private fun describe(address: BridgeAddress, bridgeName: String, mixer: MixerStatus) =
        "$bridgeName (bridge $address) → " + if (mixer.connected) mixer.name else "no mixer connected"

    companion object {
        /** Device id prefix for bridges found on the network (followed by the bridge's name). */
        const val FOUND_PREFIX = "bridge-found:"

        /** An older bridge's refusal names the version it speaks: "…; this bridge speaks v3". */
        private val OLDER_BRIDGE = Regex("""this bridge speaks v(\d+)""")

        /** Upload data per frame, and how much may be unacknowledged (bounds the delay it adds to MIDI). */
        const val UPLOAD_CHUNK = 32 * 1024
        const val UPLOAD_WINDOW = 3L * UPLOAD_CHUNK

        /** A download is acknowledged every this many bytes (the bridge keeps 128 KB in flight). */
        const val ACK_EVERY = 32L * 1024

        /** A download fails if the bridge sends nothing for this long. */
        val DOWNLOAD_SILENCE = 30.seconds
    }
}

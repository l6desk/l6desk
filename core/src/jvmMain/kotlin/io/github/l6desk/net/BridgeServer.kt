package io.github.l6desk.net

import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.MidiDeviceInfo
import io.github.l6desk.midi.MidiMessage
import io.github.l6desk.midi.MidiStreamParser
import io.github.l6desk.midi.MidiTransport
import io.github.l6desk.padcard.PadCardHost
import io.github.l6desk.padcard.PadCardJson
import io.github.l6desk.padcard.PadResult
import io.github.l6desk.player.MusicPlayer
import io.github.l6desk.recordings.ByteSink
import io.github.l6desk.recordings.RecCommand
import io.github.l6desk.recordings.RecStage
import io.github.l6desk.recordings.RecState
import io.github.l6desk.recordings.Recording
import io.github.l6desk.recordings.RecordingsHost
import io.github.l6desk.recordings.RecordingsJson
import io.github.l6desk.player.PlayerJson
import io.github.l6desk.player.Track
import io.github.l6desk.player.UploadReceiver
import io.github.l6desk.player.UploadStage
import io.github.l6desk.player.UploadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Wi-Fi bridge (CLAUDE.md, route C): accepts one controlling client at a time over TCP and
 * forwards MIDI both ways between it and [mixer] (on the desktop, whatever the app is connected
 * to — normally the L6max over USB). A second client gets [Frame.Busy]. [onClientMidi] sees
 * every message from the client (for logging). With a [player] (the headless bridge's music
 * player), version-2 clients also get its tracks and state and can send it commands. With
 * [powerOff] (the headless bridge, when allowed), version-4 clients may power the computer off.
 * With [info], version-5 clients are told about the bridge and its computer: after Welcome, on
 * every [infoUpdates] emission (a warning appearing or clearing), and when they ask. With
 * [padCard] (the Pi, which mounts the L6max's card in file transfer mode), version-7 clients can
 * set the sound pads' sounds from the music library; with [recordings] (the same card), version-8
 * clients can download its recordings.
 */
class BridgeServer(
    private val mixer: MidiTransport,
    private val scope: CoroutineScope,
    private val bridgeName: String = defaultBridgeName(),
    private val keepalive: Duration = 2.seconds,
    private val timeout: Duration = 5.seconds,
    private val player: MusicPlayer? = null,
    /** Shuts this computer down; called after the client's [Frame.PowerOff] has been acknowledged. */
    private val powerOff: (() -> Unit)? = null,
    /** Apps must answer this PIN to connect (protocol v6); null for none. */
    pin: String? = null,
    /** Describes the bridge as it is now (protocol v5). */
    private val info: (() -> BridgeInfo)? = null,
    private val infoUpdates: Flow<*>? = null,
    private val padCard: PadCardHost? = null,
    private val recordings: RecordingsHost? = null,
    /** Why sessions end and how downloads go (the headless bridge's log). */
    private val log: (String) -> Unit = {},
    private val onClientMidi: (MidiMessage) -> Unit = {},
) : BridgeHost {
    private val _status = MutableStateFlow(BridgeStatus())
    override val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val activeClient = AtomicReference<String?>(null)

    /** The PIN apps must answer (protocol v6), or null for none. Can be changed while running. */
    @Volatile
    var pin: String? = pin?.takeIf { BridgePin.isValid(it) }
        private set

    override fun setPin(pin: String?) {
        this.pin = pin?.takeIf { BridgePin.isValid(it) }
    }

    private val pinAttempts = PinAttempts()

    /**
     * The desk this bridge serves is (or was last trying to get) to its mixer through a bridge
     * itself. Kept through errors and reconnect attempts, which don't say what they connect to.
     */
    private val deskOnBridge = MutableStateFlow(false)

    init {
        scope.launch {
            mixer.state.collect { s ->
                when (s) {
                    is ConnectionState.Connected -> deskOnBridge.value = s.device.kind == MidiDeviceInfo.Kind.NETWORK
                    is ConnectionState.Connecting -> deskOnBridge.value = s.device.kind == MidiDeviceInfo.Kind.NETWORK
                    ConnectionState.Disconnected -> deskOnBridge.value = false
                    is ConnectionState.Error -> Unit
                }
            }
        }
    }

    override fun start(port: Int) {
        stop()
        serverJob = scope.launch(Dispatchers.IO) {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket(port)
                serverSocket = ss
                _status.value = BridgeStatus(running = true, port = ss.localPort, addresses = localAddresses())
                while (isActive) {
                    val socket = runInterruptible { ss.accept() }
                    launch { serve(JavaByteSocket(socket)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A closed socket here means stop() was called: not an error.
                if (ss == null || !ss.isClosed) {
                    _status.value = BridgeStatus(running = false, port = port, error = "Bridge stopped: ${e.message ?: e::class.simpleName}")
                }
            }
        }
    }

    override fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        _status.value = _status.value.copy(running = false, client = null)
    }

    private suspend fun serve(socket: ByteSocket) {
        val session = FrameSession(socket, keepalive, timeout)
        val forwarding = CoroutineScope(scope.coroutineContext + SupervisorJob() + Dispatchers.IO)
        var clientName: String? = null
        var version = 0
        val parser = MidiStreamParser()
        // The upload being received (one at a time), and how much of it has arrived.
        var upload: UploadReceiver? = null
        var uploadId = 0
        var received = 0L
        fun uploadState(status: UploadStatus) = session.send(Frame.UploadState(PlayerJson.encodeUploadStatus(status)))
        // The recording being downloaded (v8, one at a time), and how much of it the client has.
        var recJob: Job? = null
        var recId = 0
        val recAcked = MutableStateFlow(0L)
        fun recState(state: RecState) = session.send(Frame.RecState(RecordingsJson.encodeState(state)))
        // Waiting for the PIN answer (v6): who asked, and the challenge they were sent.
        var pending: String? = null
        var nonce: ByteArray? = null

        fun sendInfo() {
            val describe = info ?: return
            if (version < BridgeProtocol.BRIDGE_INFO_VERSION) return
            runCatching { BridgeInfo.encode(describe()) }.getOrNull()?.let { session.send(Frame.BridgeInfo(it)) }
        }

        /** Lets [name] in: it's in control from now on, and is sent everything it can use. */
        suspend fun welcome(name: String) {
            if (!activeClient.compareAndSet(null, name)) {
                session.sendNow(Frame.Busy("Another device is in control: ${activeClient.get()}"))
                throw BridgeBusyException("Refused $name")
                }
            clientName = name
            _status.update { it.copy(client = name) }
            session.send(Frame.Welcome(version, bridgeName, mixerStatus(mixer.state.value)))
            if (powerOff != null && version >= BridgeProtocol.POWER_OFF_VERSION) session.send(Frame.CanPowerOff)
            sendInfo()
            if (infoUpdates != null && info != null && version >= BridgeProtocol.BRIDGE_INFO_VERSION) {
                forwarding.launch { infoUpdates.collect { sendInfo() } }
                }
            forwarding.launch { mixer.incoming.collect { m -> session.send(Frame.Midi(m.toBytes().map { it.toByte() }.toByteArray())) } }
            forwarding.launch { mixer.state.collect { s -> session.send(Frame.Status(mixerStatus(s))) } }
            // The desk switched to a bridge mid-session: end it (see above).
            forwarding.launch { deskOnBridge.collect { if (it) socket.close() } }
            if (player != null && version >= 2) {
                forwarding.launch { player.tracks.collect { session.send(Frame.Tracks(tracksJson(it))) } }
                forwarding.launch { player.state.collect { session.send(Frame.Playback(PlayerJson.encodeState(it))) } }
                }
            if (padCard != null && version >= BridgeProtocol.PAD_CARD_VERSION) {
                forwarding.launch { padCard.card.collect { session.send(Frame.PadCard(PadCardJson.encodeState(it))) } }
            }
            if (recordings != null && version >= BridgeProtocol.RECORDINGS_VERSION) {
                forwarding.launch { recordings.recordings.collect { session.send(Frame.Recordings(recordingsJson(it))) } }
            }
        }
        try {
            session.run { frame ->
                when {
                    clientName == null && pending == null && frame is Frame.Hello -> {
                        val name = "${frame.clientName.ifBlank { "client" }} (${socket.remoteAddress})"
                        if (frame.version !in BridgeProtocol.MIN_VERSION..BridgeProtocol.VERSION) {
                            session.sendNow(Frame.Busy("Protocol v${frame.version} isn't supported; this bridge speaks v${BridgeProtocol.VERSION}"))
                            throw BridgeProtocolException("Client protocol v${frame.version}")
                        }
                        version = minOf(frame.version, BridgeProtocol.VERSION)
                        // A desk that is itself on a bridge has no mixer to offer: the tablet should
                        // use that bridge directly. (It's also how a desktop connected to its own
                        // Bridge mode would loop every message round forever.)
                        if (deskOnBridge.value) {
                            session.sendNow(Frame.Busy("This desk is connected through a bridge itself: connect to that bridge directly"))
                            throw BridgeBusyException("Refused $name: the desk is on a bridge")
                        }
                        if (pin == null) {
                            welcome(name)
                        } else {
                            if (version < BridgeProtocol.PIN_VERSION) {
                                session.sendNow(Frame.Busy("This bridge has a PIN: update the app to 0.16 or newer"))
                                throw BridgeBusyException("Refused $name: too old for a PIN")
                            }
                            if (pinAttempts.lockedOut(socket.remoteAddress)) {
                                session.sendNow(Frame.Busy("Too many wrong PINs: try again in a minute"))
                                throw BridgeBusyException("Refused $name: too many wrong PINs")
                            }
                            val challenge = secureRandomBytes(16)
                            nonce = challenge
                            pending = name
                            session.send(Frame.PinRequired(challenge))
                        }
                    }
                    clientName == null && frame is Frame.PinAnswer -> {
                        val name = pending ?: return@run
                        val challenge = nonce ?: return@run
                        val expected = pin
                        if (expected == null || BridgePin.matches(expected, challenge, frame.mac)) {
                            pinAttempts.succeeded(socket.remoteAddress)
                            welcome(name)
                        } else {
                            pinAttempts.failed(socket.remoteAddress)
                            session.sendNow(Frame.Busy(BridgeProtocol.WRONG_PIN))
                            throw BridgeBusyException("Wrong PIN from $name")
                        }
                    }
                    clientName != null && frame is Frame.Command -> {
                        // A command this bridge can't read (e.g. from a newer app) is ignored.
                        runCatching { PlayerJson.decodeCommand(frame.json) }.getOrNull()?.let { player?.handle(it) }
                    }
                    clientName != null && version >= BridgeProtocol.UPLOAD_VERSION && frame is Frame.UploadStart -> {
                        upload?.abort()
                        upload = null
                        val start = runCatching { PlayerJson.decodeUploadStart(frame.json) }.getOrNull() ?: return@run
                        uploadId = start.id
                        received = 0
                        try {
                            upload = (player ?: throw IllegalStateException("This bridge has no music player")).startUpload(start, ::uploadState)
                        } catch (e: Exception) {
                            uploadState(UploadStatus(start.id, UploadStage.FAILED, message = e.message ?: "The bridge can't take this file"))
                        }
                    }
                    clientName != null && frame is Frame.UploadData -> {
                        val receiver = upload
                        if (receiver != null && frame.id == uploadId) {
                            try {
                                if (frame.bytes.isEmpty()) {
                                    upload = null
                                    receiver.finish()
                                } else {
                                    receiver.write(frame.bytes, 0, frame.bytes.size)
                                    received += frame.bytes.size
                                    uploadState(UploadStatus(frame.id, UploadStage.RECEIVING, received))
                                }
                            } catch (e: Exception) {
                                upload = null
                                receiver.abort()
                                uploadState(UploadStatus(frame.id, UploadStage.FAILED, received, message = "The bridge couldn't store it: ${e.message}"))
                            }
                        }
                    }
                    clientName != null && frame is Frame.UploadCancel -> if (frame.id == uploadId) {
                        upload?.abort()
                        upload = null
                    }
                    clientName != null && frame is Frame.PowerOff && powerOff != null && version >= BridgeProtocol.POWER_OFF_VERSION -> {
                        // Queued like any frame (writing it directly could cut into one being sent); it's
                        // out long before the shutdown it starts reaches this process.
                        session.send(Frame.PowerOff)
                        powerOff.invoke()
                    }
                    clientName != null && frame is Frame.BridgeInfoRequest -> sendInfo()
                    clientName != null && frame is Frame.RecCommand && recordings != null && version >= BridgeProtocol.RECORDINGS_VERSION -> {
                        when (val c = runCatching { RecordingsJson.decodeCommand(frame.json) }.getOrNull()) {
                            is RecCommand.Start -> {
                                recJob?.cancel()
                                recId = c.id
                                recAcked.value = 0
                                recJob = forwarding.launch { sendRecording(recordings, c, session, recAcked, ::recState) }
                            }
                            is RecCommand.Ack -> if (c.id == recId) recAcked.value = maxOf(recAcked.value, c.received)
                            is RecCommand.Cancel -> if (c.id == recId) recJob?.cancel()
                            null -> Unit
                        }
                    }
                    clientName != null && frame is Frame.PadCommand && padCard != null && version >= BridgeProtocol.PAD_CARD_VERSION -> {
                        val command = runCatching { PadCardJson.decodeCommand(frame.json) }.getOrNull() ?: return@run
                        // Copying a song to the card takes a while: not on the session's reader.
                        forwarding.launch {
                            val result = try {
                                padCard.handle(command)
                            } catch (e: Exception) {
                                PadResult(command.id, e.message ?: "That didn't work", error = true)
                            }
                            session.send(Frame.PadResult(PadCardJson.encodeResult(result)))
                        }
                    }
                    clientName != null && frame is Frame.Midi -> parser.feed(frame.bytes).forEach {
                        onClientMidi(it)
                        mixer.send(it)
                    }
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Client went away or was refused; its slot is freed below.
            clientName?.let { log("$it: session ended (${e.message ?: e::class.simpleName})") }
        } finally {
            upload?.abort()
            forwarding.cancel()
            clientName?.let { name ->
                activeClient.compareAndSet(name, null)
                _status.update { if (it.client == name) it.copy(client = null) else it }
            }
        }
    }

    /**
     * Sends a recording's files ([RecordingsHost.prepare]) as data frames behind MIDI, keeping at
     * most [REC_WINDOW] bytes unacknowledged, then DONE (or FAILED, with why).
     */
    private suspend fun sendRecording(host: RecordingsHost, c: RecCommand.Start, session: FrameSession, acked: MutableStateFlow<Long>, state: (RecState) -> Unit) {
        try {
            state(RecState(c.id, RecStage.PREPARING))
            var lastReport = 0L
            val prepared = host.prepare(c.folder, c.options) { done, total ->
                val now = System.currentTimeMillis()
                if (now - lastReport >= 500) {
                    lastReport = now
                    state(RecState(c.id, RecStage.PREPARING, done = done, total = total))
                }
            }
            state(RecState(c.id, RecStage.SENDING, files = prepared.files, total = prepared.files.sumOf { it.bytes }))
            val started = System.currentTimeMillis()
            val chunk = ByteArray(REC_CHUNK)
            var fill = 0
            var sent = 0L
            suspend fun flush() {
                if (fill == 0) return
                acked.first { sent - it <= REC_WINDOW }
                session.sendBulk(Frame.RecData(c.id, chunk.copyOf(fill)))
                sent += fill
                fill = 0
            }
            val sink = ByteSink { bytes, offset, length ->
                var at = offset
                var left = length
                while (left > 0) {
                    val n = minOf(left, REC_CHUNK - fill)
                    bytes.copyInto(chunk, fill, at, at + n)
                    fill += n
                    at += n
                    left -= n
                    if (fill == REC_CHUNK) flush()
                }
            }
            val clipped = mutableMapOf<String, Long>()
            prepared.files.forEachIndexed { i, f -> prepared.write(i, sink).takeIf { it > 0 }?.let { clipped[f.name] = it } }
            flush()
            state(RecState(c.id, RecStage.DONE, files = prepared.files, clipped = clipped))
            val seconds = (System.currentTimeMillis() - started) / 1000.0
            log("Sent recording ${c.folder}: ${sent / 1_000_000} MB in ${"%.0f".format(seconds)} s (${"%.1f".format(sent / 1e6 / maxOf(seconds, 0.001))} MB/s)")
        } catch (e: CancellationException) {
            log("Recording ${c.folder}: download stopped")
            throw e
        } catch (e: Throwable) {
            // Anything at all (an Error too): the app must hear that it failed, not wait.
            log("Recording ${c.folder}: download failed (${e.message ?: e::class.simpleName})")
            state(RecState(c.id, RecStage.FAILED, message = e.message ?: "The bridge couldn't send the recording"))
        }
    }

    /** The recordings as JSON, the oldest left out if they wouldn't all fit in one frame. */
    private fun recordingsJson(list: List<Recording>?): String {
        var shown = list ?: return RecordingsJson.encodeList(null)
        while (true) {
            val json = RecordingsJson.encodeList(shown)
            if (json.encodeToByteArray().size <= BridgeProtocol.MAX_PAYLOAD || shown.isEmpty()) return json
            shown = shown.dropLast(maxOf(1, shown.size / 10))
        }
    }

    /** The track list as JSON, shortened if it wouldn't fit in one frame. */
    private fun tracksJson(tracks: List<Track>): String {
        var list = tracks
        while (true) {
            val json = PlayerJson.encodeTracks(list)
            if (json.encodeToByteArray().size <= BridgeProtocol.MAX_PAYLOAD || list.isEmpty()) return json
            list = list.dropLast(maxOf(1, list.size / 10))
        }
    }

    private fun mixerStatus(state: ConnectionState) = when (state) {
        is ConnectionState.Connected -> MixerStatus(true, state.device.name)
        else -> MixerStatus(false)
    }

    companion object {
        /** Recording data per frame, and how much may be unacknowledged (bounds the delay it adds to MIDI). */
        const val REC_CHUNK = 32 * 1024
        const val REC_WINDOW = 4L * REC_CHUNK

        fun defaultBridgeName(): String =
            System.getenv("HOSTNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "L6Desk bridge"

        /** This computer's IPv4 addresses on active, non-loopback network interfaces. */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
        }.getOrDefault(emptyList())
    }
}

/**
 * Wrong PIN guesses, by the address they came from: after [allowed] in a row, that address is
 * refused for [lockout] milliseconds, so a stray phone can't simply try every PIN.
 */
internal class PinAttempts(private val allowed: Int = 5, private val lockout: Long = 60_000, private val now: () -> Long = System::currentTimeMillis) {
    private class Record(var fails: Int = 0, var until: Long = 0)
    private val records = java.util.concurrent.ConcurrentHashMap<String, Record>()

    fun lockedOut(address: String): Boolean = (records[address]?.until ?: 0) > now()

    fun failed(address: String) {
        val r = records.getOrPut(address) { Record() }
        synchronized(r) {
            r.fails++
            if (r.fails >= allowed) {
                r.until = now() + lockout
                r.fails = 0
            }
        }
    }

    fun succeeded(address: String) {
        records.remove(address)
    }
}

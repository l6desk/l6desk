package io.github.l6desk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.LoggingTransport
import io.github.l6desk.midi.MidiTransport
import io.github.l6desk.midi.MultiTransport
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.net.BridgeAddress
import io.github.l6desk.net.BridgeHost
import io.github.l6desk.net.NetworkMidiTransport
import kotlinx.coroutines.CoroutineScope
import io.github.l6desk.model.Control
import io.github.l6desk.model.GlobalParam
import io.github.l6desk.model.SceneSlot
import io.github.l6desk.ui.DeskView
import io.github.l6desk.ui.SceneNotice
import io.github.l6desk.persistence.AppSettings
import io.github.l6desk.persistence.AppStorage
import io.github.l6desk.persistence.MemoryStore
import io.github.l6desk.persistence.ProfileLibrary
import io.github.l6desk.sync.ConnectionManager
import io.github.l6desk.sync.MixerController
import io.github.l6desk.ui.ConnectionPanel
import io.github.l6desk.ui.DeskActions
import io.github.l6desk.ui.ExitDialog
import io.github.l6desk.ui.PinDialog
import io.github.l6desk.ui.PlayerPanel
import io.github.l6desk.player.PLAYER_CHANNEL_LEVEL
import io.github.l6desk.player.PLAYER_START_VOLUME
import io.github.l6desk.player.PlayerCommand
import io.github.l6desk.player.UploadQueue
import io.github.l6desk.player.PlayerHost
import io.github.l6desk.player.UploadSource
import io.github.l6desk.ui.MidiLogOverlay
import io.github.l6desk.ui.MixerScreen
import io.github.l6desk.ui.RenameDialog
import io.github.l6desk.ui.SetupPanel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import io.github.l6desk.midi.MidiDeviceInfo
import io.github.l6desk.report.AppLog
import io.github.l6desk.report.ProblemReport
import io.github.l6desk.report.ReportContext
import io.github.l6desk.report.ReportIssue
import io.github.l6desk.report.ReportPackage
import io.github.l6desk.report.buildReport
import io.github.l6desk.report.eventsText
import io.github.l6desk.report.isoNow
import io.github.l6desk.report.platformDetails
import io.github.l6desk.ui.ReportDialog
import io.github.l6desk.ui.BridgeWarning
import io.github.l6desk.ui.ReportStage
import io.github.l6desk.ui.encodePng
import kotlin.math.roundToInt

/**
 * The platform asking the app to close (the window's close button, Android's back gesture). The
 * app answers with `onExit`, straight away or after asking whether to shut down the Pi bridge too.
 */
class ExitRequests {
    internal var pending by mutableStateOf(false)

    fun request() {
        pending = true
    }
}

/** A report being written: everything captured when REPORT was pressed (see [ReportDialog]). */
private class ReportDraft(
    val id: String,
    val created: String,
    val install: String,
    val screenshot: ImageBitmap?,
    val png: ByteArray?,
    val context: ReportContext,
    val events: String,
) {
    fun report(message: String): ProblemReport = buildReport(id, created, message, context, install)
}

/**
 * Root composable shared by the Android and desktop apps. [transports] are offered together
 * (e.g. USB and the simulator). The desk, profiles and settings are loaded from [storage] at
 * start and saved as they change.
 */
@OptIn(FlowPreview::class)
@Composable
fun App(
    transports: List<MidiTransport>,
    storage: AppStorage = remember { AppStorage(MemoryStore()) },
    modifier: Modifier = Modifier,
    /** Desktop: creates the Wi-Fi bridge server around the app's (logged) transport. */
    bridgeFactory: ((MidiTransport, CoroutineScope) -> BridgeHost)? = null,
    /** Android: keep the screen awake while connected (a desk mustn't sleep mid-show). */
    keepScreenOn: ((Boolean) -> Unit)? = null,
    /** Lets the user pick music files to upload to the bridge's player (empty if they cancel). */
    pickAudioFiles: (suspend () -> List<UploadSource>)? = null,
    /** Close requests from the platform; [onExit] then actually closes the app. */
    exitRequests: ExitRequests? = null,
    onExit: () -> Unit = {},
    /** REPORT: the install's signing key and how to save the zip. Null hides REPORT. */
    reports: ReportSupport? = null,
    /** Desktop: a music player on this computer, for when the L6max is on its USB (made on first use). */
    localPlayer: (() -> PlayerHost)? = null,
    /** Extra facts for problem reports (desktop: every MIDI device Java Sound sees). */
    diagnostics: (() -> Map<String, String>)? = null,
    /** Desktop: the sound pads' sounds on the mixer's card (SETUP → PAD SOUNDS…). */
    padCard: io.github.l6desk.padcard.PadCardSupport? = null,
    /** RECORDINGS: downloading the mixer's recordings from its card (desktop). */
    recordings: io.github.l6desk.recordings.RecordingsSupport? = null,
    /** Where recordings downloaded through the Pi bridge are saved (any platform). */
    recordingSaver: io.github.l6desk.recordings.RecordingSaver? = null,
) {
    val scope = rememberCoroutineScope()
    // Recent events for problem reports (connections, errors, SEND DESK…).
    val appLog = remember { AppLog() }
    var library by remember { mutableStateOf(storage.loadProfiles()) }
    var settings by remember { mutableStateOf(storage.loadSettings()) }
    var scenes by remember { mutableStateOf(storage.loadScenes()) }
    var currentScene by remember { mutableStateOf<SceneSlot?>(null) }
    var sceneNotice by remember { mutableStateOf<SceneNotice?>(null) }

    val network = remember(transports) {
        transports.filterIsInstance<NetworkMidiTransport>().firstOrNull()
            ?.also { net ->
                net.setBridges(settings.bridges.mapNotNull(BridgeAddress::parse))
                net.setPins(settings.bridgePins)
            }
    }
    val multi = remember(transports) { MultiTransport(transports, scope) }
    val logged = remember(multi) { LoggingTransport(multi, scope) }
    val controller = remember(logged) {
        MixerController(logged, scope, storage.loadDesk(), library.active).also { it.autoPushOnConnect = settings.autoPushOnConnect }
    }
    val manager = remember(logged) {
        ConnectionManager(logged, scope, rememberedId = settings.lastDeviceId) { device ->
            settings = settings.copy(lastDeviceId = device.id)
            storage.saveSettings(settings)
        }
    }

    fun updateLibrary(new: ProfileLibrary) {
        library = new
        storage.saveProfiles(new)
        if (controller.profile != new.active) controller.profile = new.active
    }

    val bridge = remember(logged) { bridgeFactory?.invoke(logged, scope) }

    fun updateSettings(new: AppSettings) {
        val old = settings
        settings = new
        storage.saveSettings(new)
        controller.autoPushOnConnect = new.autoPushOnConnect
        if (new.bridges != old.bridges) network?.setBridges(new.bridges.mapNotNull(BridgeAddress::parse))
        if (bridge != null && new.bridgeModePin != old.bridgeModePin) bridge.setPin(new.bridgeModePin.ifEmpty { null })
        if (bridge != null && new.bridgeMode != old.bridgeMode) {
            if (new.bridgeMode) bridge.start(new.bridgePort) else bridge.stop()
        }
    }

    // Bridge mode survives a restart; stop the server when the app closes.
    DisposableEffect(bridge) {
        bridge?.setPin(settings.bridgeModePin.ifEmpty { null })
        if (bridge != null && settings.bridgeMode) bridge.start(settings.bridgePort)
        onDispose { bridge?.stop() }
    }
    val bridgeStatus = bridge?.status?.collectAsState()?.value
    // Never offer this computer's own Bridge mode as somewhere to connect.
    LaunchedEffect(bridgeStatus?.running, bridgeStatus?.port, bridgeStatus?.addresses) {
        val b = bridgeStatus
        network?.setOwnBridge(
            if (b?.running == true) (b.addresses + "127.0.0.1").map { BridgeAddress(it, b.port) }.toSet() else emptySet(),
        )
    }

    // A scene recalled on the mixer: either recall the app's scene in that slot (link on, and one is
    // stored), or warn that the desk no longer matches the mixer.
    LaunchedEffect(controller) {
        controller.mixerSceneRecalls.collect { slot ->
            val appScene = scenes[slot]
            appLog.add("Scene ${slot.name} recalled on the mixer")
            if (settings.linkMixerScenes && appScene != null) {
                controller.recallScene(appScene)
                currentScene = slot
                sceneNotice = SceneNotice.Linked(slot)
            } else {
                controller.markMixerChanged()
                currentScene = null
                sceneNotice = SceneNotice.OutOfStep(slot)
            }
        }
    }
    // The "linked" confirmation goes by itself; the out-of-step warning stays until dealt with.
    LaunchedEffect(sceneNotice) {
        if (sceneNotice is SceneNotice.Linked) {
            kotlinx.coroutines.delay(4_000)
            sceneNotice = null
        }
    }

    // Save the desk shortly after each change, and once more when the app closes.
    LaunchedEffect(controller) { controller.state.drop(1).debounce(500).collect { storage.saveDesk(it) } }
    DisposableEffect(controller) { onDispose { storage.saveDesk(controller.state.value) } }

    var showConnections by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    var showPadCard by remember { mutableStateOf(false) }
    var showRecordings by remember { mutableStateOf(false) }
    // RECORDINGS: through the Pi when the connected bridge offers it, else this computer's USB.
    val bridgeRecordings = remember(network, recordingSaver) {
        if (network != null && recordingSaver != null) io.github.l6desk.recordings.BridgeRecordingsSupport(network, recordingSaver) else null
    }
    val bridgeOffersRecordings by (network?.recordingsOffered ?: remember { MutableStateFlow(false) }).collectAsState()
    val recordingsSupport = if (bridgeOffersRecordings && bridgeRecordings != null) bridgeRecordings else recordings
    // PAD SOUNDS: through the Pi when the connected bridge offers it (any platform), else this
    // computer's USB (desktop).
    val bridgePadCard = remember(network) { network?.let { io.github.l6desk.padcard.BridgePadCardSupport(it, scope) } }
    val bridgeOffersPads by (network?.padCardOffered ?: remember { MutableStateFlow(false) }).collectAsState()
    val padSounds = if (bridgeOffersPads && bridgePadCard != null) bridgePadCard else padCard
    var renaming by remember { mutableStateOf<Int?>(null) }
    // Always start on the main mix; the SENDS view reopens on the send used last.
    var view by remember { mutableStateOf(DeskView.MAIN) }

    val state by controller.state.collectAsState()
    val connection by controller.connection.collectAsState()
    val needsPush by controller.needsPush.collectAsState()
    val pads by controller.pads.collectAsState()
    // The player: the bridge's, when it has one; otherwise this computer's, while the L6max is on
    // its USB (desktop). Made only when first needed, so its music folder isn't created otherwise.
    val bridgePlayer by (network?.player ?: remember { MutableStateFlow(null) }).collectAsState()
    val mixerOnUsb = (connection as? ConnectionState.Connected)?.device?.kind == MidiDeviceInfo.Kind.USB
    val local = remember(localPlayer) { lazy { localPlayer?.invoke() } }
    val playerHost: PlayerHost? = when {
        bridgePlayer != null -> network
        mixerOnUsb -> local.value
        else -> null
    }
    val player by (playerHost?.player ?: remember { MutableStateFlow(null) }).collectAsState()
    var showPlayer by remember { mutableStateOf(false) }
    val uploadQueue = remember(playerHost) { playerHost?.let { host -> UploadQueue(scope) { source, playlist, onStatus -> host.upload(source, playlist, onStatus) } } }
    val uploads by (uploadQueue?.items ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    // Opening the player changes nothing: the user chooses where it plays (CH7 or CH8). That
    // channel's level and USB switch are noted, then it goes to USB at full level (the player's own
    // volume, starting at 10 %, does the work, so its slider covers the channel's whole range).
    // Choosing the other channel gives the first back as it was. The other channel is never raised
    // (an unused channel at full brought up its hum on the real L6max). Everything comes back when
    // the player is closed; if a song is still playing then, when it stops, so DONE never changes
    // the level mid-song. The channel chosen is the one held, so it survives closing mid-song and
    // restarts (held levels are saved).
    val chosenOutput = settings.playerHeldLevels?.keys?.firstOrNull()?.let { ch -> io.github.l6desk.player.PlayerOutput.entries.firstOrNull { it.channel == ch } }
    val stopped = player?.state?.playing == null
    LaunchedEffect(showPlayer, player != null, stopped) {
        val held = settings.playerHeldLevels
        val heldUsb = settings.playerHeldUsb
        if ((held != null || heldUsb != null) && !showPlayer && player != null && stopped) {
            held?.forEach { (ch, level) -> controller.setParam(ch, ChannelParam.LEVEL, level) }
            heldUsb?.forEach { (ch, usb) -> controller.setParam(ch, ChannelParam.USB_INPUT, usb) }
            updateSettings(settings.copy(playerHeldLevels = null, playerHeldUsb = null))
        }
    }

    /** Chooses where the player plays (see above): notes the channel, switches it to USB at full level. */
    fun choosePlayerOutput(output: io.github.l6desk.player.PlayerOutput) {
        val ch = output.channel
        val levels = settings.playerHeldLevels.orEmpty()
        val usbs = settings.playerHeldUsb.orEmpty()
        // The channel left goes back as it was.
        levels.filterKeys { it != ch }.forEach { (c, level) -> controller.setParam(c, ChannelParam.LEVEL, level) }
        usbs.filterKeys { it != ch }.forEach { (c, usb) -> controller.setParam(c, ChannelParam.USB_INPUT, usb) }
        val channel = state.channel(ch)
        val level = levels[ch] ?: channel.level
        // Only a known switch is given back: an unknown one is left on USB.
        val usb = usbs[ch] ?: channel.usbInput?.let { if (it) 127 else 0 }
        updateSettings(settings.copy(playerHeldLevels = mapOf(ch to level), playerHeldUsb = usb?.let { mapOf(ch to it) }))
        controller.setParam(ch, ChannelParam.USB_INPUT, 127)
        controller.setParam(ch, ChannelParam.LEVEL, PLAYER_CHANNEL_LEVEL)
        playerHost?.command(PlayerCommand.SetOutput(output))
        if (levels.isEmpty()) playerHost?.command(PlayerCommand.SetVolume(PLAYER_START_VOLUME))
    }

    // Closing: with the option on and a Pi bridge that can power off, ask whether to shut it down.
    val canPowerOffBridge by (network?.canPowerOff ?: remember { MutableStateFlow(false) }).collectAsState()
    var askingExit by remember { mutableStateOf(false) }
    var poweringOff by remember { mutableStateOf(false) }
    val exitPending = exitRequests?.pending == true
    LaunchedEffect(exitPending) {
        if (!exitPending) return@LaunchedEffect
        exitRequests?.pending = false
        val onPi = connection is ConnectionState.Connected && canPowerOffBridge
        if (settings.powerOffBridgeOnExit && onPi) askingExit = true else onExit()
    }

    // ---- Problem reports -------------------------------------------------------------------
    // What happened before a report, in its events: connections, the bridge, instant mute, the player.
    val bridgeSession by (network?.bridgeSession ?: remember { MutableStateFlow(null) }).collectAsState()
    LaunchedEffect(connection) { appLog.add(connectionEvent(connection)) }
    LaunchedEffect(bridgeSession?.name, bridgeSession?.protocol) {
        bridgeSession?.let { appLog.add("Bridge \"${it.name}\" speaks protocol v${it.protocol}") }
    }
    LaunchedEffect(bridgeSession?.mixer) {
        bridgeSession?.mixer?.let { appLog.add(if (it.connected) "Mixer at the bridge: ${it.name}" else "No mixer at the bridge") }
    }
    LaunchedEffect(canPowerOffBridge) { if (canPowerOffBridge) appLog.add("The bridge can power off") }
    val bridgeInfo by (network?.bridgeInfo ?: remember { MutableStateFlow(null) }).collectAsState()
    LaunchedEffect(bridgeInfo?.version, bridgeInfo?.kind) {
        bridgeInfo?.let { appLog.add("Bridge runs L6Desk ${it.version} (${it.kind}) on ${it.board ?: it.os}") }
    }
    LaunchedEffect(bridgeInfo?.warnings) { bridgeInfo?.warnings?.forEach { appLog.add("Bridge warning: $it") } }
    // Shown on the desk until dismissed; a new dip brings it back (dismissing remembers the count).
    val bridgeWarnings = BridgeWarning.from(bridgeInfo)
    var dismissedWarnings by remember { mutableStateOf(emptySet<String>()) }
    // A warning that clears is forgotten, so it shows again if it comes back.
    LaunchedEffect(bridgeWarnings.map { it.key }) { dismissedWarnings = dismissedWarnings intersect bridgeWarnings.map { it.key }.toSet() }
    LaunchedEffect(controller) {
        controller.state.map { it.masterMute }.distinctUntilChanged().drop(1).collect { appLog.add(if (it) "INSTANT MUTE on" else "INSTANT MUTE off") }
    }
    LaunchedEffect(playerHost) {
        val playerFlow = playerHost?.player ?: return@LaunchedEffect
        launch { playerFlow.map { it?.state?.error }.distinctUntilChanged().filterNotNull().collect { appLog.add("Player error: $it") } }
        playerFlow.map { p -> p?.state?.let { if (it.playing == null) "stopped" else if (it.paused) "paused" else "playing" } }
            .distinctUntilChanged().filterNotNull().drop(1).collect { appLog.add("Player $it") }
    }
    LaunchedEffect(bridgeStatus?.error) { bridgeStatus?.error?.let { appLog.add("Bridge mode error: $it") } }
    LaunchedEffect(bridgeStatus?.client) { if (bridgeStatus?.running == true) appLog.add(if (bridgeStatus.client != null) "Bridge mode: a client connected" else "Bridge mode: no client") }

    // The whole app is recorded into this layer as it's drawn, so REPORT can take a screenshot.
    val captureLayer = rememberGraphicsLayer()
    val density = LocalDensity.current.density
    val uriHandler = LocalUriHandler.current
    var draft by remember { mutableStateOf<ReportDraft?>(null) }
    var reportMessage by remember { mutableStateOf("") }
    var reportScreenshot by remember { mutableStateOf(true) }
    var reportDetails by remember { mutableStateOf(false) }
    var reportStage by remember { mutableStateOf<ReportStage>(ReportStage.Writing) }

    fun startReport() {
        val support = reports ?: return
        if (draft != null) return
        scope.launch {
            // From SETUP: close it first, so the screenshot shows the desk (two frames: recompose, then draw).
            if (showSetup) {
                showSetup = false
                withFrameNanos { }
                withFrameNanos { }
            }
            val shot = runCatching { captureLayer.toImageBitmap() }.getOrNull()
            val png = withContext(Dispatchers.Default) { shot?.encodePng() }
                ?.takeIf { it.size <= ReportPackage.LIMITS.getValue(ReportPackage.SCREENSHOT) }
            // The key is made on first use (the Android Keystore can take a moment): not on the UI thread.
            val install = runCatching { withContext(Dispatchers.Default) { ReportPackage.fingerprint(support.key.publicKey) } }.getOrDefault("unknown")
            // Fresh from a v5 bridge: under-voltage and temperature as they are now.
            val bridgeInfo = network?.refreshBridgeInfo()
            val screen = shot?.let { "${it.width}×${it.height} px, density ${(density * 100).roundToInt() / 100.0}" }
            val context = ReportContext(
                connection = connection,
                bridge = bridgeSession,
                canPowerOff = canPowerOffBridge,
                player = player,
                profile = library.active,
                settings = settings,
                platform = platformDetails(),
                screen = screen,
                bridgeInfo = bridgeInfo,
                diagnostics = runCatching { withContext(Dispatchers.Default) { diagnostics?.invoke() } }.getOrNull().orEmpty(),
            )
            reportMessage = ""
            reportScreenshot = png != null
            reportDetails = false
            reportStage = ReportStage.Writing
            draft = ReportDraft(
                ReportPackage.newId(), isoNow(), install, shot.takeIf { png != null }, png, context,
                eventsText(appLog.events.value, logged.log.value, library.active),
            )
        }
    }

    /**
     * Builds the zip (off the UI thread: signing can take a moment) and hands it to [deliver],
     * which returns a sentence saying where it went, or null if the user cancelled.
     */
    fun deliverReport(deliver: suspend (fileName: String, bytes: ByteArray) -> String?) {
        val d = draft ?: return
        val support = reports ?: return
        reportStage = ReportStage.Saving
        scope.launch {
            reportStage = try {
                val report = d.report(reportMessage)
                val bytes = withContext(Dispatchers.Default) {
                    ReportPackage.build(report, d.events, d.png.takeIf { reportScreenshot }, support.key)
                }
                val where = deliver(ReportPackage.fileName(d.id), bytes)
                if (where == null) {
                    ReportStage.Writing
                } else {
                    appLog.add("Report ${d.id} saved")
                    ReportStage.Saved(where)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ReportStage.Failed("Couldn't save the report: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    val devices by logged.devices.collectAsState()
    val isConnected = connection is ConnectionState.Connected
    LaunchedEffect(isConnected) { keepScreenOn?.invoke(isConnected) }

    val actions = remember(controller) {
        object : DeskActions {
            override fun setParam(channel: Int, param: ChannelParam, value: Int) = controller.setParam(channel, param, value)
            override fun setMute(channel: Int, on: Boolean) = controller.setMute(channel, on)
            override fun setSolo(channel: Int, on: Boolean) = controller.setSolo(channel, on)
            override fun rename(channel: Int) { renaming = channel }
            override fun setTouching(channel: Int, param: ChannelParam, touching: Boolean) =
                controller.setTouching(Control.Channel(channel, param), touching)
            override fun setMaster(value: Int) = controller.setMaster(value)
            override fun setMasterMute(on: Boolean) = controller.setMasterMute(on)
            override fun clearSolo() = controller.clearSolo()
            override fun setPad(pad: Int, down: Boolean) = controller.setPad(pad, down)
            override fun pushAll() {
                appLog.add("SEND DESK")
                controller.pushAll()
                sceneNotice = null
            }
            override fun dismissSceneNotice() { sceneNotice = null }
            override fun dismissBridgeWarning(warning: BridgeWarning) { dismissedWarnings = dismissedWarnings + warning.key }
            override fun openConnections() { showConnections = true }
            override fun toggleMidiLog() = updateSettings(settings.copy(showMidiLog = !settings.showMidiLog))
            override fun openSetup() { showSetup = true }
            override fun openReport() = startReport()
            override fun openPlayer() { showPlayer = true }
            override fun openSends() { view = DeskView.SENDS }
            override fun backToMix() { view = DeskView.MAIN }
            override fun selectSend(param: ChannelParam) = updateSettings(settings.copy(lastSend = param))
            override fun setGlobal(param: GlobalParam, value: Int) = controller.setGlobal(param, value)
            override fun storeScene(slot: SceneSlot) {
                scenes = scenes.store(slot, controller.state.value)
                storage.saveScenes(scenes)
                currentScene = slot
            }
            override fun recallScene(slot: SceneSlot) {
                val scene = scenes[slot] ?: return
                appLog.add("App scene ${slot.name} recalled")
                controller.recallScene(scene)
                currentScene = slot
                sceneNotice = null
            }
        }
    }

    Box(
        modifier.fillMaxSize()
            .drawWithContent {
                captureLayer.record { this@drawWithContent.drawContent() }
                drawLayer(captureLayer)
            }
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        MixerScreen(
            state, connection, needsPush, actions,
            midiLogVisible = settings.showMidiLog,
            view = view,
            selectedSend = settings.lastSend,
            storedScenes = scenes.scenes.keys,
            currentScene = currentScene,
            sceneNotice = sceneNotice,
            pads = pads,
            player = player,
            canReport = reports != null,
            bridgeWarnings = bridgeWarnings.filter { it.key !in dismissedWarnings },
            padNotes = library.active.ccMap.padNotes,
            bridgeLine = bridgeStatus?.takeIf { settings.bridgeMode }?.let { b ->
                when {
                    b.error != null -> "BRIDGE ERROR"
                    b.client != null -> "BRIDGE: ${b.client}"
                    b.running -> "BRIDGE: waiting on ${b.addresses.firstOrNull() ?: "port"}:${b.port}"
                    else -> "BRIDGE: starting…"
                }
            },
        )
        if (settings.showMidiLog) {
            MidiLogOverlay(logged, connection, controller.profile, Modifier.align(Alignment.BottomStart).padding(16.dp))
        }
        renaming?.let { ch ->
            RenameDialog(
                channel = ch,
                current = state.channel(ch).name,
                onRename = { controller.setName(ch, it); renaming = null },
                onDismiss = { renaming = null },
            )
        }
        val remote = player
        if (showPlayer && remote != null) {
            PlayerPanel(
                player = remote,
                usbInput = { ch -> state.channel(ch).usbInput },
                onCommand = { playerHost?.command(it) },
                noAudioHint = playerHost?.noAudioHint ?: "",
                chosenOutput = chosenOutput,
                onChooseOutput = ::choosePlayerOutput,
                onDismiss = { showPlayer = false },
                uploads = uploads,
                onUpload = pickAudioFiles?.takeIf { uploadQueue != null }?.let { pick ->
                    {
                        val playlist = remote.state.playlist
                        if (playlist != null) scope.launch { pick().takeIf { it.isNotEmpty() }?.let { uploadQueue?.add(it, playlist) } }
                    }
                },
                onCancelUpload = { uploadQueue?.cancel(it) },
                onClearUploads = { uploadQueue?.clearFinished() },
            )
        }
        if (showSetup) {
            SetupPanel(
                library = library,
                settings = settings,
                connected = connection is ConnectionState.Connected,
                onLibraryChange = ::updateLibrary,
                onSettingsChange = ::updateSettings,
                readMap = {
                    val device = (connection as? ConnectionState.Connected)?.device ?: error("Not connected")
                    multi.readCcMap(device)
                },
                learn = { controller.learnNextControlChange() },
                learnNote = { controller.learnNextNote() },
                onDismiss = { showSetup = false },
                bridgeStatus = bridgeStatus,
                onResetMix = { controller.resetMix().also { if (it) appLog.add("RESET MIX") } },
                onReport = reports?.let { ::startReport },
                onPadSounds = padSounds?.let { { showSetup = false; showPadCard = true } },
                onRecordings = recordingsSupport?.let { { showSetup = false; showRecordings = true } },
            )
        }
        if (showPadCard && padSounds != null) {
            io.github.l6desk.ui.PadCardPanel(padSounds, onDismiss = { showPadCard = false })
        }
        if (showRecordings && recordingsSupport != null) {
            // Connected to a bridge that can't offer the card's recordings: say why, rather than
            // only looking on this computer's USB.
            val session = bridgeSession
            val note = if (session == null || bridgeOffersRecordings) null else if (session.protocol < io.github.l6desk.net.BridgeProtocol.RECORDINGS_VERSION) {
                "${session.name} is too old to download recordings: update it (on a Pi: pi-setup.sh install with this version's bridge pack). Below: this computer's own USB."
            } else {
                "${session.name} isn't set up for the mixer's card (on a Pi: run pi-setup.sh install). Below: this computer's own USB."
            }
            io.github.l6desk.ui.RecordingsPanel(
                recordingsSupport, settings.recordingOptions, onOptions = { updateSettings(settings.copy(recordingOptions = it)) }, note = note,
                onDismiss = { showRecordings = false },
            )
        }
        if (showConnections) {
            ConnectionPanel(
                devices = devices,
                connection = connection,
                onSelect = { manager.select(it); showConnections = false },
                onDisconnect = { manager.disconnect() },
                onDismiss = { showConnections = false },
                onAddBridge = network?.let {
                    { address -> updateSettings(settings.copy(bridges = (settings.bridges + address.toString()).distinct())) }
                },
                onRemoveBridge = network?.let {
                    { device ->
                        if ((connection as? ConnectionState.Connected)?.device?.id == device.id) manager.disconnect()
                        updateSettings(settings.copy(bridges = settings.bridges.filterNot { b -> BridgeAddress.parse(b)?.id == device.id }))
                    }
                },
            )
        }
        val d = draft
        if (d != null && reports != null) {
            ReportDialog(
                message = reportMessage,
                onMessageChange = { reportMessage = it },
                screenshot = d.screenshot,
                includeScreenshot = reportScreenshot,
                onIncludeScreenshot = { reportScreenshot = it },
                showDetails = reportDetails,
                onShowDetails = { reportDetails = it },
                details = { ReportPackage.reportJson(d.report(reportMessage)) },
                install = d.install,
                stage = reportStage,
                onSave = { deliverReport { name, bytes -> reports.save(name, bytes)?.let { "Saved: $it." } } },
                onShare = reports.share?.let { share ->
                    { deliverReport { name, bytes -> share(name, bytes); "Sent to the app you chose." } }
                },
                onOpenGitHub = {
                    val url = ReportIssue.url(d.report(reportMessage))
                    val opened = runCatching { reports.openUrl?.invoke(url) ?: uriHandler.openUri(url) }.isSuccess
                    val stage = reportStage
                    if (!opened && stage is ReportStage.Saved) reportStage = stage.copy(browserFailed = true)
                },
                onDismiss = { draft = null },
            )
        }
        val pinRequest by (network?.pinRequest ?: remember { MutableStateFlow(null) }).collectAsState()
        pinRequest?.let { request ->
            PinDialog(
                bridge = request.device.name,
                wrong = request.wrong,
                onPin = { pin ->
                    updateSettings(settings.copy(bridgePins = settings.bridgePins + (request.device.id to pin)))
                    network?.setPin(request.device.id, pin)
                },
                onCancel = { manager.disconnect() },
            )
        }
        if (askingExit) {
            ExitDialog(
                bridgeName = (connection as? ConnectionState.Connected)?.device?.name?.substringBefore(" (bridge") ?: "the bridge",
                musicPlaying = player?.state?.playing != null,
                poweringOff = poweringOff,
                onPowerOff = {
                    poweringOff = true
                    scope.launch {
                        // Close either way: if the Pi didn't answer, it simply stays on.
                        network?.powerOffBridge()
                        onExit()
                    }
                },
                onCloseOnly = { askingExit = false; onExit() },
                onCancel = { askingExit = false },
            )
        }
    }
}

private fun connectionEvent(c: ConnectionState): String = when (c) {
    is ConnectionState.Connected -> "Connected (${routeName(c.device.kind)}): ${c.device.name}"
    is ConnectionState.Connecting -> "Connecting (${routeName(c.device.kind)}): ${c.device.name}"
    is ConnectionState.Error -> "Connection error: ${c.reason}"
    ConnectionState.Disconnected -> "Disconnected"
}

private fun routeName(kind: MidiDeviceInfo.Kind) = when (kind) {
    MidiDeviceInfo.Kind.USB -> "USB"
    MidiDeviceInfo.Kind.BLUETOOTH -> "Bluetooth"
    MidiDeviceInfo.Kind.NETWORK -> "Wi-Fi bridge"
    MidiDeviceInfo.Kind.SIMULATOR -> "simulator"
}

package io.github.l6desk.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.cancel
import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.MixerState
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the desk offscreen to build/screenshots/ for visual review (no window opens).
 * Sizes: the Galaxy Tab A11 in landscape and portrait (1340×800 px at 213 dpi), and a desktop window.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenshotRender {
    private val sample = MixerState()
        .withChannel(1) { it.copy(name = "Vocal").with(ChannelParam.LEVEL, 110).with(ChannelParam.EQ_HIGH, 90) }
        .withChannel(2) { it.copy(name = "Guitar", solo = true).with(ChannelParam.PAN, 30) }
        .withChannel(3) { it.copy(name = "Keys").withMute(true).with(ChannelParam.EQ_LOW, 30) }
        .withChannel(5) { it.copy(name = "Synth").with(ChannelParam.MONO_X2, 127).with(ChannelParam.LEVEL, 70) }
        .withChannel(7) { it.copy(name = "Laptop").with(ChannelParam.EQ_MID_FREQ, 100) }
        .copy(master = 95) // a little above unity (6)

    private fun render(
        name: String,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        state: MixerState = sample,
        view: DeskView = DeskView.MAIN,
        send: ChannelParam = ChannelParam.AUX1,
        scenes: Set<io.github.l6desk.model.SceneSlot> = emptySet(),
        current: io.github.l6desk.model.SceneSlot? = null,
        notice: SceneNotice? = null,
        pads: Set<Int> = emptySet(),
    ) =
        runDesktopComposeUiTest(widthPx, heightPx) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MixerScreen(
                        state, ConnectionState.Connected(SimulatorTransport.DEVICE), needsPush = false, actions = NoActions,
                        view = view, selectedSend = send, storedScenes = scenes, currentScene = current, sceneNotice = notice,
                        pads = pads, canReport = true,
                    )
                }
            }
            val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
            val out = File("build/screenshots/$name.png").apply { parentFile.mkdirs() }
            out.writeBytes(requireNotNull(png).bytes)
            assertTrue(out.length() > 0)
        }

    @Test fun tabletLandscape() = render("tablet-landscape", 1340, 800, 213f / 160f)
    @Test fun tabletPortrait() = render("tablet-portrait", 800, 1340, 213f / 160f)
    @Test fun desktop() = render("desktop", 1340, 800, 1f)
    private val withSends = sample
        .withChannel(1) { it.with(ChannelParam.AUX1, 100).with(ChannelParam.AUX2, 30).with(ChannelParam.EFX_SEND, 50) }
        .withChannel(2) { it.with(ChannelParam.AUX1, 70).with(ChannelParam.SUB_MIX, 90) }
        .withChannel(3) { it.with(ChannelParam.AUX1, 20) }
        .withChannel(5) { it.with(ChannelParam.AUX1, 110).with(ChannelParam.EFX_SEND, 80) }
        .withGlobal(io.github.l6desk.model.GlobalParam.EFX_TYPE, 21)

    @Test fun sendsTablet() = render(
        "sends-tablet", 1340, 800, 213f / 160f, withSends, DeskView.SENDS, ChannelParam.AUX1,
        setOf(io.github.l6desk.model.SceneSlot.A, io.github.l6desk.model.SceneSlot.B), io.github.l6desk.model.SceneSlot.B,
    )
    @Test fun sendsPortrait() = render("portrait-sends", 800, 1340, 213f / 160f, withSends, DeskView.SENDS, ChannelParam.AUX1)
    @Test fun sendsTabletEfx() = render("sends-tablet-efx", 1340, 800, 213f / 160f, withSends, DeskView.SENDS, ChannelParam.EFX_SEND)
    @Test fun sceneWarning() = render(
        "tablet-scene-warning", 1340, 800, 213f / 160f, sample,
        notice = SceneNotice.OutOfStep(io.github.l6desk.model.SceneSlot.A),
    )
    private val remotePlayer = io.github.l6desk.net.RemotePlayer(
        tracks = listOf(
            io.github.l6desk.player.Track("Saturday gig/01 Walk-on.wav", "Walk-on", 95_000, io.github.l6desk.player.TrackEnd.NEXT, "Saturday gig"),
            io.github.l6desk.player.Track("Saturday gig/02 Interval music.wav", "Interval music", 1_250_000, playlist = "Saturday gig"),
            io.github.l6desk.player.Track("Saturday gig/03 Yesterday.wav", "Yesterday", 125_000, playlist = "Saturday gig"),
            io.github.l6desk.player.Track("Saturday gig/04 Encore backing.wav", "Encore backing", 235_000, io.github.l6desk.player.TrackEnd.LOOP, "Saturday gig"),
        ),
        state = io.github.l6desk.player.PlayerState(
            ready = true, playing = "Saturday gig/01 Walk-on.wav", positionMs = 52_000, volume = 80,
            playlists = listOf("Saturday gig", "Songs", "The Beatles"), playlist = "Saturday gig", canEditPlaylists = true,
        ),
        canUpload = true,
    )
    private val uploading = listOf(
        io.github.l6desk.player.UploadItem(1, "03 - Yesterday.mp3", "Saturday gig", 4_200_000, io.github.l6desk.player.UploadItem.Stage.DONE),
        io.github.l6desk.player.UploadItem(2, "Hey Jude.flac", "Saturday gig", 38_000_000, io.github.l6desk.player.UploadItem.Stage.SENDING, sent = 17_100_000),
        io.github.l6desk.player.UploadItem(3, "Let It Be.m4a", "Saturday gig", 5_000_000),
    )

    private fun renderPlayer(name: String, editing: Boolean, armDelete: Boolean = false, widthPx: Int = 1340, heightPx: Int = 800) = runDesktopComposeUiTest(widthPx, heightPx) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(213f / 160f)) {
                MixerScreen(sample, ConnectionState.Connected(SimulatorTransport.DEVICE), needsPush = false, actions = NoActions, player = remotePlayer)
                PlayerPanel(remotePlayer, usbInput = { true }, onCommand = {}, chosenOutput = io.github.l6desk.player.PlayerOutput.CH7, onChooseOutput = {}, onDismiss = {}, uploads = if (editing) emptyList() else uploading, onUpload = {})
            }
        }
        if (editing) onNodeWithTag("player-edit").performClick()
        if (armDelete) onNodeWithTag("playlist-delete").performClick()
        waitForIdle()
        val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
        File("build/screenshots/$name.png").apply { parentFile.mkdirs() }.writeBytes(requireNotNull(png).bytes)
    }

    @Test fun playerManyPlaylists() = runDesktopComposeUiTest(1340, 800) {
        val many = remotePlayer.copy(state = remotePlayer.state.copy(playlists = listOf(
            "Saturday gig", "Songs", "The Beatles", "Christmas carols", "Wedding — first dance", "Interval music",
            "Panto 2026", "Sunday matinee", "Encores", "Soundcheck", "Kids' party", "Remembrance",
        )))
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(213f / 160f)) {
                MixerScreen(sample, ConnectionState.Connected(SimulatorTransport.DEVICE), needsPush = false, actions = NoActions, player = many)
                PlayerPanel(many, usbInput = { true }, onCommand = {}, chosenOutput = io.github.l6desk.player.PlayerOutput.CH7, onChooseOutput = {}, onDismiss = {}, onUpload = {})
            }
        }
        val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
        File("build/screenshots/tablet-player-many.png").apply { parentFile.mkdirs() }.writeBytes(requireNotNull(png).bytes)
        // What fits, then ALL (12); the other playlists, and + NEW, are in the list it opens.
        onNodeWithTag("playlist-Saturday gig").assertIsDisplayed()
        onNodeWithTag("playlist-all").assertIsDisplayed()
        onNodeWithTag("player-upload").assertIsDisplayed()
        onNodeWithTag("playlist-Remembrance").assertDoesNotExist()
        onNodeWithTag("playlist-all").performClick()
        waitForIdle()
        val chooser = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
        File("build/screenshots/tablet-player-many-chooser.png").writeBytes(requireNotNull(chooser).bytes)
        onNodeWithTag("playlist-choose-Remembrance").assertIsDisplayed()
        onNodeWithTag("playlist-new").assertIsDisplayed()
    }

    @Test fun player() = renderPlayer("tablet-player", editing = false)
    @Test fun playerEditing() = renderPlayer("tablet-player-edit", editing = true)
    @Test fun playerPortrait() = renderPlayer("portrait-player", editing = false, widthPx = 800, heightPx = 1340)
    @Test fun playerPortraitEditing() = renderPlayer("portrait-player-edit", editing = true, widthPx = 800, heightPx = 1340)
    @Test fun playerDeletingPlaylist() = renderPlayer("tablet-player-delete-playlist", editing = true, armDelete = true)

    @Test fun exitDialog() = runDesktopComposeUiTest(1340, 800) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(213f / 160f)) {
                MixerScreen(sample, ConnectionState.Connected(SimulatorTransport.DEVICE), needsPush = false, actions = NoActions)
                ExitDialog("L6Desk Bridge", musicPlaying = true, poweringOff = false, onPowerOff = {}, onCloseOnly = {}, onCancel = {})
            }
        }
        val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
        File("build/screenshots/tablet-exit.png").apply { parentFile.mkdirs() }.writeBytes(requireNotNull(png).bytes)
    }

    /** The MIDI log (top bar → MIDI) over the desk, with traffic both ways. */
    @Test fun midiLog() = runDesktopComposeUiTest(1340, 800) {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val simulator = SimulatorTransport(echo = true)
        val logged = io.github.l6desk.midi.LoggingTransport(simulator, scope)
        kotlinx.coroutines.runBlocking { logged.open(SimulatorTransport.DEVICE) }
        val ccs = io.github.l6desk.model.L6maxLayout.FACTORY_CC_MAP.assignments.values.sorted()
        for ((i, cc) in ccs.take(8).withIndex()) {
            logged.send(io.github.l6desk.midi.MidiMessage.ControlChange(1, cc, 40 + i * 9))
        }
        logged.send(io.github.l6desk.midi.MidiMessage.NoteOn(1, 60, 100))
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(300) }
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(213f / 160f)) {
                androidx.compose.foundation.layout.Box {
                    MixerScreen(
                        sample, ConnectionState.Connected(SimulatorTransport.DEVICE), needsPush = false, actions = NoActions,
                        view = DeskView.MAIN, selectedSend = ChannelParam.AUX1, pads = emptySet(), canReport = true,
                    )
                    MidiLogOverlay(
                        logged, ConnectionState.Connected(SimulatorTransport.DEVICE), io.github.l6desk.model.Profile.FACTORY,
                        androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(16.dp),
                    )
                }
            }
        }
        waitForIdle()
        val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
        File("build/screenshots/tablet-midi-log.png").apply { parentFile.mkdirs() }.writeBytes(requireNotNull(png).bytes)
        scope.cancel()
    }

    @Test fun soundPadHeld() = render("tablet-pad-held", 1340, 800, 213f / 160f, pads = setOf(2))
    @Test fun masterMute() = render("tablet-master-mute", 1340, 800, 213f / 160f, sample.copy(masterMute = true))

    private fun renderSetup(name: String, widthPx: Int, heightPx: Int, density: Float, scrolls: Boolean = false) = runDesktopComposeUiTest(widthPx, heightPx) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                MixerScreen(sample, ConnectionState.Connected(SimulatorTransport.DEVICE), needsPush = false, actions = NoActions)
                SetupPanel(
                    library = io.github.l6desk.persistence.ProfileLibrary.DEFAULT,
                    settings = io.github.l6desk.persistence.AppSettings(),
                    connected = true,
                    onLibraryChange = {}, onSettingsChange = {},
                    readMap = { error("unused") }, learn = { error("unused") }, onDismiss = {},
                    onResetMix = { true },
                    onReport = {},
                    onPadSounds = {},
                    onRecordings = {},
                )
            }
        }
        // Select a cell so the cell editor (the tallest state of the right-hand column) is shown.
        onNodeWithTag("map-1-LEVEL").performClick()
        waitForIdle()
        val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
        File("build/screenshots/$name.png").apply { parentFile.mkdirs() }.writeBytes(requireNotNull(png).bytes)
        // Every option must be reachable without scrolling on the landscape screens; in portrait
        // (one column) by scrolling.
        for (tag in listOf("option-midilog", "option-power-off", "setup-done", "reset-mix", "setup-report", "pad-sounds")) {
            if (scrolls && tag != "setup-done" && tag != "setup-report") onNodeWithTag(tag).performScrollTo()
            onNodeWithTag(tag).assertIsDisplayed()
        }
        if (scrolls) {
            onNodeWithTag("pad-sounds").performScrollTo()
            val bottom = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
            File("build/screenshots/$name-bottom.png").writeBytes(requireNotNull(bottom).bytes)
        }
    }

    /** The busiest top bar: a bridge with the player playing, and REPORT. Everything must still show. */
    @Test fun topBarWithEverything() = renderTopBar("tablet-topbar-full", 1340, 800, listOf("report-button", "midi-log-toggle"))

    /** Portrait: no REPORT or MIDI (both in SETUP), and the rest still fits. */
    @Test fun topBarPortrait() = renderTopBar("portrait-topbar-full", 800, 1340, emptyList())

    private fun renderTopBar(name: String, widthPx: Int, heightPx: Int, extra: List<String>) = runDesktopComposeUiTest(widthPx, heightPx) {
        val playing = io.github.l6desk.net.RemotePlayer(
            tracks = listOf(io.github.l6desk.player.Track("Songs/01 Walk-on.wav", "Walk-on", 90_000, playlist = "Songs")),
            state = io.github.l6desk.player.PlayerState(ready = true, playing = "Songs/01 Walk-on.wav", positionMs = 30_000),
        )
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(213f / 160f)) {
                MixerScreen(
                    sample, ConnectionState.Connected(SimulatorTransport.DEVICE.copy(name = "L6Desk Bridge (bridge 10.42.0.1) → L6max")),
                    needsPush = true, actions = NoActions, player = playing, canReport = true,
                )
            }
        }
        val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
        File("build/screenshots/$name.png").apply { parentFile.mkdirs() }.writeBytes(requireNotNull(png).bytes)
        for (tag in extra + listOf("setup-button", "send-desk", "player-button", "sends-button")) {
            onNodeWithTag(tag).assertIsDisplayed()
        }
        // Each button whole (SEND DESK was squeezed to "SEN D…" in portrait).
        assertTrue(onNodeWithTag("send-desk").fetchSemanticsNode().size.width >= 100, "SEND DESK at full width")
        onNodeWithTag("connection-status", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun renderReport(name: String, details: Boolean, stage: ReportStage) = runDesktopComposeUiTest(1340, 800) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(213f / 160f)) {
                MixerScreen(sample, ConnectionState.Connected(SimulatorTransport.DEVICE), needsPush = false, actions = NoActions, canReport = true)
                ReportDialog(
                    message = "After the Pi reconnected, CH3's fader stopped moving the mixer.",
                    onMessageChange = {},
                    screenshot = androidx.compose.ui.graphics.ImageBitmap(1340, 800),
                    includeScreenshot = true, onIncludeScreenshot = {},
                    showDetails = details, onShowDetails = {},
                    details = { "{\n  \"format\": 1,\n  \"id\": \"ABCD2345\",\n  \"message\": \"…\"\n}" },
                    install = "3f2a-91c0-77de-0b14",
                    stage = stage,
                    onSave = {}, onShare = {}, onOpenGitHub = {}, onDismiss = {},
                )
            }
        }
        val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
        File("build/screenshots/$name.png").apply { parentFile.mkdirs() }.writeBytes(requireNotNull(png).bytes)
        onNodeWithTag(if (stage is ReportStage.Saved) "report-github" else "report-save").assertIsDisplayed()
    }

    private fun renderWarnings(name: String, info: io.github.l6desk.net.BridgeInfo) = runDesktopComposeUiTest(1340, 800) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(213f / 160f)) {
                MixerScreen(
                    sample, ConnectionState.Connected(SimulatorTransport.DEVICE.copy(name = "L6Desk Bridge (bridge 10.42.0.1) → L6max")),
                    needsPush = false, actions = NoActions, canReport = true, bridgeWarnings = BridgeWarning.from(info),
                )
            }
        }
        val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
        File("build/screenshots/$name.png").apply { parentFile.mkdirs() }.writeBytes(requireNotNull(png).bytes)
        onNodeWithTag("send-desk").assertIsDisplayed()
        onNodeWithTag("bridge-warnings").assertIsDisplayed()
    }

    private val pi = io.github.l6desk.net.BridgeInfo(io.github.l6desk.net.BridgeInfo.KIND_PI, "0.16.0")
    @Test fun powerWarningNow() = renderWarnings("tablet-power-now", pi.copy(underVoltageNow = true, underVoltageCount = 1))
    @Test fun powerWarningEarlier() = renderWarnings("tablet-power-earlier", pi.copy(underVoltageNow = false, underVoltageCount = 3))
    @Test fun allBridgeWarnings() = renderWarnings("tablet-bridge-warnings", pi.copy(underVoltageNow = true, underVoltageCount = 2, temperatureC = 82.4, musicFreeMb = 120))

    @Test fun padCardPanel() = runDesktopComposeUiTest(1340, 800) {
        val support = object : io.github.l6desk.padcard.PadCardSupport {
            override val card = kotlinx.coroutines.flow.MutableStateFlow<io.github.l6desk.padcard.PadCardState?>(
                io.github.l6desk.padcard.PadCardState("/media/user/ZOOM_SD", listOf(
                    io.github.l6desk.padcard.PadSound(1, "PAD1-beeps.wav", 1200), io.github.l6desk.padcard.PadSound(2, "Walk-on sting.wav", 4200),
                    io.github.l6desk.padcard.PadSound(3, null, others = 2), io.github.l6desk.padcard.PadSound(4, null),
                )),
            )
            override fun watch(): () -> Unit = {}
            override suspend fun choose(pad: Int): String? = null
            override suspend fun clear(pad: Int) = ""
            override suspend fun eject(): String? = null
            override val backupLocation = "/home/user/Music/L6Desk/Pad backups"
        }
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(213f / 160f)) {
                MixerScreen(sample, ConnectionState.Connected(SimulatorTransport.DEVICE), needsPush = false, actions = NoActions)
                PadCardPanel(support, onDismiss = {})
            }
        }
        val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
        File("build/screenshots/tablet-pad-card.png").apply { parentFile.mkdirs() }.writeBytes(requireNotNull(png).bytes)
        onNodeWithTag("pad-card-eject").assertIsDisplayed()
    }

    @Test fun padCardPickerOnPi() = runDesktopComposeUiTest(1340, 800) {
        val support = object : io.github.l6desk.padcard.PadCardSupport {
            override val card = kotlinx.coroutines.flow.MutableStateFlow<io.github.l6desk.padcard.PadCardState?>(
                io.github.l6desk.padcard.PadCardState("at the Pi", (1..4).map { io.github.l6desk.padcard.PadSound(it, null) }),
            )
            override fun watch(): () -> Unit = {}
            override suspend fun choose(pad: Int): String? = null
            override suspend fun clear(pad: Int) = ""
            override suspend fun eject(): String? = null
            override val backupLocation = "the Pi's music folder (.pad-backups)"
            override val library = kotlinx.coroutines.flow.MutableStateFlow(
                listOf("Walk-on sting" to 4_200L, "Applause" to 9_000L, "Drum roll" to 3_500L, "Big finish" to 6_000L).mapIndexed { i, (t, ms) ->
                    io.github.l6desk.player.Track("Stings/0${i + 1} $t.wav", t, ms, playlist = "Stings")
                } + io.github.l6desk.player.Track("Set 1/01 Opener.wav", "Opener", 200_000, playlist = "Set 1"),
            )
        }
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(213f / 160f)) {
                MixerScreen(sample, ConnectionState.Connected(SimulatorTransport.DEVICE), needsPush = false, actions = NoActions)
                PadCardPanel(support, onDismiss = {})
            }
        }
        onNodeWithTag("pad-choose-2").performClick()
        onNodeWithTag("pad-pick-playlist-Stings").performClick()
        waitForIdle()
        val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
        File("build/screenshots/tablet-pad-card-pi.png").apply { parentFile.mkdirs() }.writeBytes(requireNotNull(png).bytes)
        onNodeWithTag("pad-pick-cancel").assertIsDisplayed()
    }

    @Test fun recordingsPanel() = renderRecordings("tablet-recordings", 1340, 800)
    @Test fun recordingsPortrait() = renderRecordings("portrait-recordings", 800, 1340)

    private fun renderRecordings(name: String, widthPx: Int, heightPx: Int) = runDesktopComposeUiTest(widthPx, heightPx) {
        val support = object : io.github.l6desk.recordings.RecordingsSupport {
            override val recordings = kotlinx.coroutines.flow.MutableStateFlow<List<io.github.l6desk.recordings.Recording>?>(
                listOf(RecordingsUiTest.sample("261016_104237"), RecordingsUiTest.sample("261009_200000", measured = false)),
            )
            override fun watch(): () -> Unit = {}
            override suspend fun download(recording: io.github.l6desk.recordings.Recording, options: io.github.l6desk.recordings.RecordingOptions, onProgress: (io.github.l6desk.recordings.DownloadProgress) -> Unit) = ""
            override val destination = "/home/user/Music/L6Desk/Recordings"
        }
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(213f / 160f)) {
                MixerScreen(sample, ConnectionState.Connected(SimulatorTransport.DEVICE), needsPush = false, actions = NoActions)
                RecordingsPanel(support, io.github.l6desk.recordings.RecordingOptions(io.github.l6desk.recordings.RecordingSelection.USED, normalise = true), onOptions = {}, onDismiss = {})
            }
        }
        val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
        File("build/screenshots/$name.png").apply { parentFile.mkdirs() }.writeBytes(requireNotNull(png).bytes)
        onNodeWithTag("recordings-download").assertIsDisplayed()
    }

    @Test fun pinDialog() = runDesktopComposeUiTest(1340, 800) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(213f / 160f)) {
                MixerScreen(sample, ConnectionState.Error("L6Desk Bridge (10.42.0.1) asks for its PIN"), needsPush = false, actions = NoActions)
                PinDialog("L6Desk Bridge (10.42.0.1)", wrong = true, onPin = {}, onCancel = {})
            }
        }
        val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
        File("build/screenshots/tablet-pin.png").apply { parentFile.mkdirs() }.writeBytes(requireNotNull(png).bytes)
        onNodeWithTag("pin-ok").assertIsDisplayed()
    }

    @Test fun reportDialog() = renderReport("tablet-report", details = false, ReportStage.Writing)
    @Test fun reportDialogDetails() = renderReport("tablet-report-details", details = true, ReportStage.Writing)
    @Test fun reportDialogSaved() = renderReport("tablet-report-saved", details = false, ReportStage.Saved("Saved: \"l6desk-report-ABCD2345.zip\" in the folder you chose."))

    @Test fun setupTablet() = renderSetup("setup-tablet", 1340, 800, 213f / 160f)
    @Test fun setupPortrait() = renderSetup("portrait-setup", 800, 1340, 213f / 160f, scrolls = true)
    @Test fun setupDesktopSmallWindow() = renderSetup("setup-desktop", 1340, 800, 1.25f)
    @Test fun setupDesktopLarge() = renderSetup("setup-desktop-large", 1690, 1000, 1.25f)

    private object NoActions : DeskActions {
        override fun setParam(channel: Int, param: ChannelParam, value: Int) = Unit
        override fun setMute(channel: Int, on: Boolean) = Unit
        override fun setSolo(channel: Int, on: Boolean) = Unit
        override fun rename(channel: Int) = Unit
        override fun setTouching(channel: Int, param: ChannelParam, touching: Boolean) = Unit
        override fun setMaster(value: Int) = Unit
        override fun setMasterMute(on: Boolean) = Unit
        override fun clearSolo() = Unit
        override fun setPad(pad: Int, down: Boolean) = Unit
        override fun pushAll() = Unit
        override fun openConnections() = Unit
        override fun toggleMidiLog() = Unit
        override fun openSetup() = Unit
        override fun openSends() = Unit
        override fun backToMix() = Unit
        override fun selectSend(param: ChannelParam) = Unit
        override fun setGlobal(param: io.github.l6desk.model.GlobalParam, value: Int) = Unit
        override fun storeScene(slot: io.github.l6desk.model.SceneSlot) = Unit
        override fun recallScene(slot: io.github.l6desk.model.SceneSlot) = Unit
        override fun dismissSceneNotice() = Unit
    }
}

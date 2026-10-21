package io.github.l6desk.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.assertTextContains
import io.github.l6desk.player.UploadSource
import io.github.l6desk.player.WavPlayer
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.l6desk.App
import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.MixerState
import io.github.l6desk.model.Control
import io.github.l6desk.net.BridgeServer
import io.github.l6desk.net.JavaSocketConnector
import io.github.l6desk.net.NetworkMidiTransport
import io.github.l6desk.net.RemotePlayer
import io.github.l6desk.persistence.AppSettings
import io.github.l6desk.persistence.AppStorage
import io.github.l6desk.persistence.MemoryStore
import io.github.l6desk.player.MusicPlayer
import io.github.l6desk.player.LocalPlayer
import io.github.l6desk.player.PcmOutput
import io.github.l6desk.midi.FakeTransport
import io.github.l6desk.player.PlayerCommand
import io.github.l6desk.player.PlayerOutput
import io.github.l6desk.player.PlayerState
import io.github.l6desk.player.Track
import io.github.l6desk.player.TrackEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The PLAYER panel driving a (stand-in) music player at a bridge, over real TCP on localhost. */
@OptIn(ExperimentalTestApi::class)
class PlayerUiTest {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = io.cancel()

    private class StandInPlayer : MusicPlayer {
        override val tracks = MutableStateFlow(
            listOf(Track("01 Opening.wav", "01 Opening", 383_000), Track("02 Interval.wav", "02 Interval", 250_000, TrackEnd.NEXT)),
        )
        override val state = MutableStateFlow(PlayerState(ready = true, cued = "01 Opening.wav"))
        val commands: MutableList<PlayerCommand> = Collections.synchronizedList(mutableListOf())
        override fun handle(command: PlayerCommand) {
            commands += command
            when (command) {
                is PlayerCommand.Cue -> state.update { it.copy(cued = command.file) }
                is PlayerCommand.Play -> state.update { it.copy(playing = command.file, positionMs = 12_000, cued = null) }
                PlayerCommand.Stop -> state.update { it.copy(playing = null, cued = it.playing) }
                is PlayerCommand.SetEnd -> tracks.update { l -> l.map { if (it.file == command.file) it.copy(end = command.end) else it } }
                is PlayerCommand.SetVolume -> state.update { it.copy(volume = command.volume) }
                is PlayerCommand.SetOutput -> state.update { it.copy(output = command.output) }
                else -> Unit
            }
        }
    }

    @Test
    fun nothingChangesUntilAnOutputIsChosenThenItsChannelIsHeldAndGivenBack() = runDesktopComposeUiTest(1340, 800) {
        val farMixer = SimulatorTransport().also { runBlocking { it.open(SimulatorTransport.DEVICE) } }
        val player = StandInPlayer()
        val bridge = BridgeServer(farMixer, io, "pi", player = player).also { it.start(0) }
        waitUntil(timeoutMillis = 5_000) { bridge.status.value.running }
        val address = "127.0.0.1:${bridge.status.value.port}"
        val net = NetworkMidiTransport(io, JavaSocketConnector, "UI test")
        val store = MemoryStore()
        AppStorage(store).saveSettings(AppSettings(bridges = listOf(address), lastDeviceId = "bridge:$address"))
        // CH8 is known to be on its own inputs (e.g. a microphone); CH7's switch is unknown.
        AppStorage(store).saveDesk(MixerState().withChannel(8) { it.with(ChannelParam.USB_INPUT, 0) })
        setContent { App(listOf(net, SimulatorTransport()), AppStorage(store)) }
        waitUntil(timeoutMillis = 5_000) { net.state.value is ConnectionState.Connected && net.player.value != null }
        fun level(ch: Int) = farMixer.valueOf(Control.Channel(ch, ChannelParam.LEVEL))
        fun usb(ch: Int) = farMixer.valueOf(Control.Channel(ch, ChannelParam.USB_INPUT))

        // Opening: nothing chosen, nothing changed.
        onNodeWithTag("player-button").performClick()
        onNodeWithTag("player-choose-output", useUnmergedTree = true).assertExists()
        onNodeWithTag("track-02 Interval.wav").performClick() // a tap only cues
        waitUntil(timeoutMillis = 3_000) { player.state.value.cued == "02 Interval.wav" }
        Thread.sleep(300)
        assertTrue((7..8).none { level(it) == 127 || usb(it) == 127 }, "opening alone changes nothing")
        assertEquals(null, AppStorage(store).loadSettings().playerHeldLevels)
        assertEquals(100, player.state.value.volume, "nor the player's volume")

        // PLAY before choosing asks for the output.
        onNodeWithTag("player-play").performClick()
        waitForIdle()
        assertTrue(player.commands.none { it is PlayerCommand.Play })
        onNodeWithTag("player-choose-output", useUnmergedTree = true).assertExists()

        // Choosing CH8: noted (level 100, own inputs), then USB at full level; player volume 10 %.
        onNodeWithTag("player-output-CH8").performClick()
        waitUntil(timeoutMillis = 3_000) { level(8) == 127 && usb(8) == 127 }
        waitUntil(timeoutMillis = 3_000) { player.state.value.output == PlayerOutput.CH8 && player.state.value.volume == 10 }
        assertEquals(mapOf(8 to 100), AppStorage(store).loadSettings().playerHeldLevels)
        assertEquals(mapOf(8 to 0), AppStorage(store).loadSettings().playerHeldUsb)
        assertTrue(level(7) != 127 && usb(7) != 127, "CH7 untouched")

        // Choosing CH7: CH8 goes back (level, own inputs); CH7 to USB at full level.
        onNodeWithTag("player-output-CH7").performClick()
        waitUntil(timeoutMillis = 3_000) { level(7) == 127 && usb(7) == 127 && level(8) == 100 && usb(8) == 0 }
        assertEquals(mapOf(7 to 100), AppStorage(store).loadSettings().playerHeldLevels)
        assertEquals(null, AppStorage(store).loadSettings().playerHeldUsb, "CH7's switch was unknown: left on USB later")
        assertEquals(10, player.state.value.volume, "choosing again doesn't reset the volume")

        onNodeWithTag("player-volume").performTouchInput { swipe(centerLeft, center) } // drag up to half
        waitUntil(timeoutMillis = 3_000) { player.state.value.volume in 48..52 }
        onNodeWithTag("track-end-01 Opening.wav").performClick() // Stop → Play next
        waitUntil(timeoutMillis = 3_000) { player.tracks.value[0].end == TrackEnd.NEXT }

        // PLAY now plays straight away (CH7 is on USB).
        onNodeWithTag("player-play").performClick()
        waitUntil(timeoutMillis = 3_000) { player.commands.any { it == PlayerCommand.Play("02 Interval.wav") } }
        waitUntil(timeoutMillis = 3_000) { net.player.value?.state?.playing == "02 Interval.wav" }

        // DONE mid-song leaves the level alone; reopening shows CH7 still chosen.
        onNodeWithTag("player-done").performClick()
        onNodeWithTag("now-playing").assertExistsNow()
        Thread.sleep(300)
        assertEquals(127, level(7), "DONE mid-song leaves the level alone")
        onNodeWithTag("player-button").performClick()
        onNodeWithTag("player-choose-output", useUnmergedTree = true).assertDoesNotExist()
        val volume = player.state.value.volume
        onNodeWithTag("player-stop").performClick()
        waitUntil(timeoutMillis = 3_000) { player.state.value.playing == null }
        assertEquals(volume, player.state.value.volume, "reopening while it played didn't reset the volume")
        assertEquals(127, level(7), "still open: still full")

        // Closed and stopped: CH7's level back; next time, nothing is chosen.
        onNodeWithTag("player-done").performClick()
        waitUntil(timeoutMillis = 3_000) { level(7) == 100 }
        assertEquals(null, AppStorage(store).loadSettings().playerHeldLevels)
        onNodeWithTag("player-button").performClick()
        onNodeWithTag("player-choose-output", useUnmergedTree = true).assertExists()
    }

    @Test
    fun playlistsUploadReorderAndDelete() = runDesktopComposeUiTest(1340, 800) {
        val dir = java.nio.file.Files.createTempDirectory("l6desk-ui-music").toFile()
        try {
            wav(java.io.File(dir, "Abba/01 SOS.wav"))
            wav(java.io.File(dir, "The Beatles/01 Yesterday.wav"))
            val farMixer = SimulatorTransport().also { runBlocking { it.open(SimulatorTransport.DEVICE) } }
            val player = WavPlayer(dir, io, { error("no playback here") }, { true }, rescanInterval = 200.milliseconds, converter = null, minFreeBytes = 0)
            val bridge = BridgeServer(farMixer, io, "pi", player = player).also { it.start(0) }
            waitUntil(timeoutMillis = 5_000) { bridge.status.value.running }
            val address = "127.0.0.1:${bridge.status.value.port}"
            val net = NetworkMidiTransport(io, JavaSocketConnector, "UI test")
            val store = MemoryStore()
            AppStorage(store).saveSettings(AppSettings(bridges = listOf(address), lastDeviceId = "bridge:$address"))
            var picks = listOf<UploadSource>()
            setContent { App(listOf(net, SimulatorTransport()), AppStorage(store), pickAudioFiles = { picks }) }
            waitUntil(timeoutMillis = 5_000) { net.state.value is ConnectionState.Connected && net.player.value != null }

            onNodeWithTag("player-button").performClick()
            onNodeWithTag("playlist-The Beatles").performClick()
            waitUntil(timeoutMillis = 3_000) { player.state.value.playlist == "The Beatles" }
            onNodeWithText("Yesterday").assertExists() // the order number is hidden
            onNodeWithText("01 Yesterday").assertDoesNotExist()

            onNodeWithTag("playlist-new").performClick()
            onNodeWithTag("playlist-name").performTextInput("Saturday gig")
            onNodeWithTag("playlist-create").performClick()
            waitUntil(timeoutMillis = 3_000) { net.player.value?.state?.playlist == "Saturday gig" }

            picks = listOf(WavSource("03 - Help.wav"), WavSource("Hey Jude.wav"))
            onNodeWithTag("player-upload").performClick()
            waitUntil(timeoutMillis = 10_000) { net.player.value?.shown?.map { it.title } == listOf("Help", "Hey Jude") }
            waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Added").fetchSemanticsNodes().size == 2 }
            onNodeWithTag("uploads-clear").performClick()
            onNodeWithText("Added").assertDoesNotExist()

            onNodeWithTag("player-edit").performClick()
            onNodeWithTag("track-down-Saturday gig/01 Help.wav").performClick()
            waitUntil(timeoutMillis = 3_000) { net.player.value?.shown?.map { it.title } == listOf("Hey Jude", "Help") }
            assertEquals(listOf("01 Hey Jude.wav", "02 Help.wav"), java.io.File(dir, "Saturday gig").list().orEmpty().filterNot { it.startsWith(".") }.sorted())

            onNodeWithTag("track-delete-Saturday gig/01 Hey Jude.wav").performClick() // arms
            waitForIdle()
            assertEquals(2, player.tracks.value.count { it.playlist == "Saturday gig" }, "one tap doesn't delete")
            onNodeWithTag("track-delete-Saturday gig/01 Hey Jude.wav").performClick() // confirms
            waitUntil(timeoutMillis = 3_000) { net.player.value?.shown?.map { it.file } == listOf("Saturday gig/01 Help.wav") }

            // Type over a song's title to rename it; Enter sends it.
            onNodeWithTag("track-title-Saturday gig/01 Help.wav").performTextReplacement("Help!")
            onNodeWithTag("track-title-Saturday gig/01 Help.wav").performImeAction()
            waitUntil(timeoutMillis = 3_000) { net.player.value?.shown?.map { it.file } == listOf("Saturday gig/01 Help!.wav") }
            assertEquals(listOf("01 Help!.wav"), java.io.File(dir, "Saturday gig").list().orEmpty().filterNot { it.startsWith(".") })

            // Rename the playlist shown, then delete it (two taps).
            onNodeWithTag("playlist-rename").performClick()
            onNodeWithTag("playlist-name").performTextReplacement("Sunday matinee")
            onNodeWithTag("playlist-create").performClick()
            waitUntil(timeoutMillis = 3_000) { net.player.value?.state?.playlist == "Sunday matinee" }
            assertEquals(listOf("01 Help!.wav"), java.io.File(dir, "Sunday matinee").list().orEmpty().filterNot { it.startsWith(".") })
            assertTrue(!java.io.File(dir, "Saturday gig").exists())

            onNodeWithTag("playlist-delete").performClick() // arms, and says what goes
            waitForIdle()
            onNodeWithTag("playlist-edit-hint", useUnmergedTree = true).assertTextContains("and its song from the Pi", substring = true)
            assertTrue(java.io.File(dir, "Sunday matinee").exists(), "one tap doesn't delete")
            onNodeWithTag("playlist-delete").performClick() // confirms
            waitUntil(timeoutMillis = 3_000) { net.player.value?.state?.playlists == listOf("Abba", "The Beatles") }
            assertTrue(!java.io.File(dir, "Sunday matinee").exists())
            onNodeWithText("SOS").assertExists() // another playlist is shown
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun wav(f: java.io.File, ms: Int = 200) {
        f.parentFile.mkdirs()
        f.writeBytes(wavBytes(ms))
    }

    private fun wavBytes(ms: Int = 200): ByteArray {
        val frames = 48 * ms
        val format = javax.sound.sampled.AudioFormat(48_000f, 16, 2, true, false)
        val out = java.io.ByteArrayOutputStream()
        javax.sound.sampled.AudioSystem.write(
            javax.sound.sampled.AudioInputStream(java.io.ByteArrayInputStream(ByteArray(frames * 4)), format, frames.toLong()),
            javax.sound.sampled.AudioFileFormat.Type.WAVE, out,
        )
        return out.toByteArray()
    }

    private inner class WavSource(override val name: String) : UploadSource {
        private val bytes = wavBytes()
        private var at = 0
        override val size = bytes.size.toLong()
        override suspend fun read(buffer: ByteArray): Int {
            if (at >= bytes.size) return -1
            val n = minOf(buffer.size, bytes.size - at)
            bytes.copyInto(buffer, 0, at, at + n)
            at += n
            return n
        }
        override fun close() = Unit
    }

    @Test
    fun withManyPlaylistsTheShownOneStaysInTheRowAndAllListsEveryOne() = runDesktopComposeUiTest(1340, 800) {
        val names = (1..12).map { "Playlist %02d".format(it) }
        val commands = mutableListOf<PlayerCommand>()
        var state by androidx.compose.runtime.mutableStateOf(PlayerState(ready = true, playlists = names, playlist = names[0], canEditPlaylists = true))
        setContent {
            PlayerPanel(
                RemotePlayer(state = state, canUpload = true), usbInput = { true },
                onCommand = { c ->
                    commands += c
                    if (c is PlayerCommand.SelectPlaylist) state = state.copy(playlist = c.name)
                },
                chosenOutput = PlayerOutput.CH7, onChooseOutput = {}, onDismiss = {},
            )
        }
        onNodeWithTag("playlist-all").performClick()
        onNodeWithTag("playlist-choose-Playlist 11").performClick()
        waitForIdle()
        assertEquals(PlayerCommand.SelectPlaylist("Playlist 11"), commands.last())
        onNodeWithTag("playlist-chooser").assertDoesNotExist()
        onNodeWithTag("playlist-Playlist 11").assertExists() // the one shown joins the row
        onNodeWithTag("playlist-all").performClick()
        onNodeWithTag("playlist-new").performClick()
        onNodeWithTag("playlist-name").assertExists() // + NEW is in the list
    }

    @Test
    fun anOlderBridgeOffersNoPlaylistRenameOrDelete() = runDesktopComposeUiTest(1340, 800) {
        val farMixer = SimulatorTransport().also { runBlocking { it.open(SimulatorTransport.DEVICE) } }
        val player = StandInPlayer().apply {
            tracks.update { l -> l.map { it.copy(playlist = "Set") } }
            state.update { it.copy(playlists = listOf("Set"), playlist = "Set", canEditPlaylists = false) }
        }
        val bridge = BridgeServer(farMixer, io, "pi", player = player).also { it.start(0) }
        waitUntil(timeoutMillis = 5_000) { bridge.status.value.running }
        val address = "127.0.0.1:${bridge.status.value.port}"
        val net = NetworkMidiTransport(io, JavaSocketConnector, "UI test")
        val store = MemoryStore()
        AppStorage(store).saveSettings(AppSettings(bridges = listOf(address), lastDeviceId = "bridge:$address"))
        setContent { App(listOf(net, SimulatorTransport()), AppStorage(store)) }
        waitUntil(timeoutMillis = 5_000) { net.player.value?.state?.playlist == "Set" }
        onNodeWithTag("player-button").performClick()
        onNodeWithTag("player-edit").performClick()
        waitForIdle()
        onNodeWithTag("playlist-rename").assertDoesNotExist()
        onNodeWithTag("playlist-delete").assertDoesNotExist()
        onNodeWithTag("track-title-01 Opening.wav").assertDoesNotExist() // song titles can't be typed over either
    }

    @Test
    fun withTheL6maxOnUsbTheDesktopsOwnPlayerAppearsAndIsOnlyMadeThen() = runDesktopComposeUiTest(1340, 800) {
        val dir = java.nio.file.Files.createTempDirectory("l6desk-local").toFile()
        try {
            wav(java.io.File(dir, "L6Desk/Encores/01 Hey Jude.wav"))
            val usb = FakeTransport(FakeTransport.usb("L6max [hw:2,0,1]"))
            var made = 0
            val silent = object : PcmOutput {
                override fun write(bytes: ByteArray, length: Int) = Unit
                override fun pause() = Unit
                override fun resume() = Unit
                override val framesPlayed = 0L
                override fun drain() = Unit
                override fun close() = Unit
            }
            val makeLocal = {
                made++
                LocalPlayer(java.io.File(dir, "L6Desk"), io, { silent }, { true }, converter = null)
            }
            var connectUsb by androidx.compose.runtime.mutableStateOf(false)
            setContent {
                App(if (connectUsb) listOf(usb) else listOf(SimulatorTransport()), localPlayer = makeLocal)
            }
            waitForIdle()
            onNodeWithTag("player-button").assertDoesNotExist() // the simulator: no player
            assertEquals(0, made, "nothing made (no music folder) until the L6max is on USB")

            connectUsb = true
            waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("player-button").assertExists() }.isSuccess }
            assertEquals(1, made)
            onNodeWithTag("player-button").performClick()
            onNodeWithText("Hey Jude").assertExists() // songs from this computer's library
            onNodeWithTag("playlist-Encores").assertExists()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun noPlayerButtonWithoutABridgePlayer() = runDesktopComposeUiTest(1340, 800) {
        val sim = SimulatorTransport()
        setContent { App(listOf(sim), AppStorage(MemoryStore())) }
        waitUntil { sim.state.value is ConnectionState.Connected }
        onNodeWithTag("player-button").assertDoesNotExist()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsNow() = assertExists()

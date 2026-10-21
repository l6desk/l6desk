package io.github.l6desk.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.l6desk.App
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.padcard.PadCardState
import io.github.l6desk.padcard.PadCardSupport
import io.github.l6desk.padcard.PadSound
import io.github.l6desk.player.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/** SETUP → PAD SOUNDS with a stand-in card. */
@OptIn(ExperimentalTestApi::class)
class PadCardUiTest {
    private class StandIn : PadCardSupport {
        override val card = MutableStateFlow<PadCardState?>(null)
        var watching = 0
        val calls = mutableListOf<String>()
        override fun watch(): () -> Unit { watching++; return { watching-- } }
        override suspend fun choose(pad: Int): String { calls += "choose $pad"; return "Pad $pad now plays Jingle.wav" }
        override suspend fun clear(pad: Int): String { calls += "clear $pad"; return "Pad $pad has no sound now." }
        override suspend fun eject(): String { calls += "eject"; return "Card ejected." }
        override val backupLocation = "~/Music/L6Desk/Pad backups"
    }

    @Test
    fun findsTheCardThenChoosesRemovesAndEjects() = runDesktopComposeUiTest(1340, 800) {
        val card = StandIn()
        setContent { App(listOf(SimulatorTransport()), padCard = card) }
        onNodeWithTag("setup-button").performClick()
        onNodeWithTag("pad-sounds").performClick()
        onNodeWithTag("pad-card-waiting").assertExists() // instructions until the card appears
        assertEquals(1, card.watching, "looking for the card while the panel is open")

        card.card.value = PadCardState("/media/user/ZOOM_SD", listOf(
            PadSound(1, "PAD1-beeps.wav", 1200), PadSound(2, null), PadSound(3, null, others = 2), PadSound(4, null),
        ))
        waitForIdle()
        onNodeWithTag("pad-card-found").assertExists()
        onNodeWithTag("pad-file-1", useUnmergedTree = true).assertTextContains("PAD1-beeps.wav", substring = true)
        onNodeWithTag("pad-file-3", useUnmergedTree = true).assertTextContains("the mixer picks one", substring = true)
        onNodeWithTag("pad-clear-2").assertDoesNotExist() // nothing to remove

        onNodeWithTag("pad-choose-2").performClick()
        waitUntil(timeoutMillis = 3_000) { card.calls == listOf("choose 2") }
        waitForIdle()
        onNodeWithTag("pad-card-message", useUnmergedTree = true).assertTextContains("Jingle.wav", substring = true)

        onNodeWithTag("pad-clear-1").performClick() // arms
        waitForIdle()
        assertEquals(listOf("choose 2"), card.calls, "one tap doesn't remove")
        onNodeWithTag("pad-clear-1").performClick()
        waitUntil(timeoutMillis = 3_000) { "clear 1" in card.calls }

        onNodeWithTag("pad-card-eject").performClick()
        waitUntil(timeoutMillis = 3_000) { "eject" in card.calls }
        onNodeWithTag("pad-card-done").performClick()
        waitForIdle()
        onNodeWithTag("pad-card-panel").assertDoesNotExist()
        assertEquals(0, card.watching, "stops looking when closed")
    }

    /** Through the Pi: CHOOSE… picks from its music library in the panel, not a file dialog. */
    private class PiStandIn : PadCardSupport {
        override val card = MutableStateFlow<PadCardState?>(PadCardState("at the Pi", (1..4).map { PadSound(it, null) }))
        val calls = mutableListOf<String>()
        override fun watch(): () -> Unit = {}
        override suspend fun choose(pad: Int): String? { calls += "dialog"; return null }
        override suspend fun clear(pad: Int) = ""
        override suspend fun eject() = ""
        override val backupLocation = "the Pi's music folder (.pad-backups)"
        override val connectTo = "the Pi"
        override val library = MutableStateFlow(listOf(
            Track("Openers/01 Fanfare.wav", "Fanfare", 3_000, playlist = "Openers"),
            Track("Stings/01 Ta-da.wav", "Ta-da", 1_000, playlist = "Stings"),
        ))
        override suspend fun chooseTrack(pad: Int, track: Track): String { calls += "pad $pad: ${track.file}"; return "${track.title}.wav is on the card for pad $pad." }
    }

    @Test
    fun throughThePiASongIsPickedFromTheLibrary() = runDesktopComposeUiTest(1340, 800) {
        val card = PiStandIn()
        setContent { PadCardPanel(card, onDismiss = {}) }
        onNodeWithTag("pad-choose-3").performClick()
        onNodeWithTag("pad-pick-song-Openers/01 Fanfare.wav").assertExists() // the first playlist
        onNodeWithTag("pad-pick-song-Stings/01 Ta-da.wav").assertDoesNotExist()
        onNodeWithTag("pad-pick-playlist-Stings").performClick()
        onNodeWithTag("pad-pick-song-Stings/01 Ta-da.wav").performClick()
        waitUntil(timeoutMillis = 3_000) { card.calls == listOf("pad 3: Stings/01 Ta-da.wav") }
        waitForIdle()
        onNodeWithTag("pad-card-message", useUnmergedTree = true).assertTextContains("Ta-da.wav", substring = true)
        onNodeWithTag("pad-row-1").assertExists() // back to the pads

        onNodeWithTag("pad-choose-1").performClick()
        onNodeWithTag("pad-pick-cancel").performClick()
        onNodeWithTag("pad-row-1").assertExists()
        assertEquals(listOf("pad 3: Stings/01 Ta-da.wav"), card.calls, "never a file dialog")

        card.library.value = emptyList()
        onNodeWithTag("pad-choose-2").performClick()
        onNodeWithTag("pad-pick-empty").assertExists()
    }

    @Test
    fun noPadSoundsButtonWithoutSupport() = runDesktopComposeUiTest(1340, 800) {
        setContent { App(listOf(SimulatorTransport())) }
        onNodeWithTag("setup-button").performClick()
        onNodeWithTag("pad-sounds").assertDoesNotExist()
    }
}

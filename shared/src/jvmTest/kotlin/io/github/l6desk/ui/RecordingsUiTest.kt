package io.github.l6desk.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.l6desk.App
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.recordings.DownloadProgress
import io.github.l6desk.recordings.RecordedFile
import io.github.l6desk.recordings.Recording
import io.github.l6desk.recordings.RecordingFormat
import io.github.l6desk.recordings.RecordingOptions
import io.github.l6desk.recordings.RecordingSelection
import io.github.l6desk.recordings.RecordingsSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/** SETUP → RECORDINGS with a stand-in card. */
@OptIn(ExperimentalTestApi::class)
class RecordingsUiTest {
    companion object {
        fun sample(folder: String, measured: Boolean = true): Recording {
            val tracks = (1..8).map { ch ->
                RecordedFile(if (ch <= 4) "TRACK0${ch}_01.wav" else "TRACK0${ch}_ST_01.wav", ch, ch > 4, 1, 74_000_000, peakDb = if (!measured) null else if (ch == 1) -2.8 else if (ch == 5) -12.2 else -60.0)
            }
            val masters = listOf(RecordedFile("MASTER_01.wav", 0, true, 1, 148_000_000), RecordedFile("MASTER_02.wav", 0, true, 2, 148_000_000))
            return Recording(folder, 386_443, tracks + masters, 1_190_000_000)
        }
    }

    private class StandIn : RecordingsSupport {
        override val recordings = MutableStateFlow<List<Recording>?>(null)
        val downloads = mutableListOf<Pair<String, RecordingOptions>>()
        var gate: CompletableDeferred<Unit>? = null
        var watching = 0
        override fun watch(): () -> Unit { watching++; return { watching-- } }
        override suspend fun download(recording: Recording, options: RecordingOptions, onProgress: (DownloadProgress) -> Unit): String {
            downloads += recording.folder to options
            onProgress(DownloadProgress("MASTER_02.wav", 50, 100))
            gate?.await()
            return "1 file (141 MB) saved"
        }
        override val destination = "/home/user/Music/L6Desk/Recordings"
        var opened = 0
        override fun openFolder(recording: Recording): Boolean { opened++; return true }
    }

    @Test
    fun theNewestIsPickedAndDownloadedWithTheOptions() = runDesktopComposeUiTest(1340, 800) {
        val support = StandIn()
        setContent { App(listOf(SimulatorTransport()), recordings = support) }
        onNodeWithTag("setup-button").performClick()
        onNodeWithTag("recordings").performClick()
        onNodeWithTag("recordings-waiting").assertExists()
        assertEquals(1, support.watching)

        support.recordings.value = listOf(sample("261016_104237"), sample("261009_200000"))
        waitForIdle()
        onNodeWithTag("recordings-summary", useUnmergedTree = true).assertTextContains("Master take 2 (the newest)", substring = true)
        onNodeWithTag("recordings-summary", useUnmergedTree = true).assertTextContains("/261016_104237", substring = true)

        onNodeWithTag("rec-what-USED").performClick()
        onNodeWithTag("rec-format-PCM24").performClick()
        onNodeWithTag("rec-normalise").performClick()
        onNodeWithTag("recordings-summary", useUnmergedTree = true).assertTextContains("CH1, CH5 + Master take 2", substring = true)

        support.gate = CompletableDeferred()
        onNodeWithTag("recordings-download").performClick()
        waitUntil(timeoutMillis = 3_000) { support.downloads.isNotEmpty() }
        waitForIdle()
        onNodeWithTag("recordings-progress-text", useUnmergedTree = true).assertTextContains("50 %", substring = true)
        onNodeWithTag("recordings-done").performClick() // can't close mid-download
        onNodeWithTag("recordings-panel").assertExists()
        support.gate?.complete(Unit)
        waitForIdle()
        onNodeWithTag("recordings-message", useUnmergedTree = true).assertTextContains("saved", substring = true)
        assertEquals(listOf("261016_104237" to RecordingOptions(RecordingSelection.USED, RecordingFormat.PCM24, normalise = true)), support.downloads)
        onNodeWithTag("recordings-open").performClick()
        assertEquals(1, support.opened)

        // An older recording can be picked instead.
        onNodeWithTag("recording-261009_200000").performClick()
        onNodeWithTag("recordings-summary", useUnmergedTree = true).assertTextContains("/261009_200000", substring = true)
        onNodeWithTag("recordings-done").performClick()
        waitForIdle()
        onNodeWithTag("recordings-panel").assertDoesNotExist()
        assertEquals(0, support.watching)
    }

    @Test
    fun aDownloadCanBeCancelled() = runDesktopComposeUiTest(1340, 800) {
        val support = StandIn().apply { recordings.value = listOf(sample("261016_104237")); gate = CompletableDeferred() }
        setContent { RecordingsPanel(support, RecordingOptions(), onOptions = {}, onDismiss = {}) }
        onNodeWithTag("recordings-download").performClick()
        waitUntil(timeoutMillis = 3_000) { support.downloads.isNotEmpty() }
        onNodeWithTag("recordings-cancel").performClick()
        waitForIdle()
        onNodeWithTag("recordings-message", useUnmergedTree = true).assertTextContains("cancelled", substring = true)
        onNodeWithTag("recordings-download").assertExists()
    }

    @Test
    fun aBridgeThatCantOfferThemSaysWhy() = runDesktopComposeUiTest(1340, 800) {
        val support = StandIn()
        setContent { RecordingsPanel(support, RecordingOptions(), onOptions = {}, onDismiss = {}, note = "L6Desk Bridge is too old to download recordings") }
        onNodeWithTag("recordings-note", useUnmergedTree = true).assertTextContains("too old", substring = true)
        onNodeWithTag("recordings-waiting").assertExists()
    }
}

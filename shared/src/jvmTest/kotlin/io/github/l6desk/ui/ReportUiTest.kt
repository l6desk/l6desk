package io.github.l6desk.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.l6desk.App
import io.github.l6desk.ReportSupport
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.report.FileReportKey
import io.github.l6desk.report.ReportCheck
import io.github.l6desk.report.ReportIssue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** REPORT, end to end: screenshot, message, a signed zip that passes the check, and the GitHub link. */
@OptIn(ExperimentalTestApi::class)
class ReportUiTest {
    private val key = FileReportKey(File(Files.createTempDirectory("report-ui").toFile(), FileReportKey.FILE_NAME))
    private var saved: Pair<String, ByteArray>? = null
    private val opened = mutableListOf<String>()
    private val support = ReportSupport(
        key,
        save = { name, bytes -> saved = name to bytes; "test folder" },
        openUrl = { opened += it },
    )

    @Test
    fun reportFromTheTopBarMakesASignedZipAndOpensTheIssue() = runDesktopComposeUiTest(1340, 800) {
        setContent { App(listOf(SimulatorTransport()), reports = support) }
        waitForIdle()
        onNodeWithTag("report-button").performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("report-dialog").assertExists() }.isSuccess }
        onNodeWithTag("report-thumbnail").assertExists() // the screenshot was taken
        onNodeWithTag("report-message").performTextInput("CH3 stopped responding")
        onNodeWithTag("report-save").performClick()
        waitUntil(timeoutMillis = 5_000) { saved != null }
        waitForIdle()
        onNodeWithTag("report-saved").assertExists()

        val (name, bytes) = assertNotNull(saved)
        File("build/report-sample.zip").writeBytes(bytes) // for trying scripts/check-report.sh
        val result = ReportCheck.check(bytes)
        assertTrue(result.ok, result.problems.toString())
        val report = assertNotNull(result.report)
        assertEquals("l6desk-report-${report.id}.zip", name)
        assertEquals("CH3 stopped responding", report.message)
        assertEquals("Simulator", report.connection.route)
        assertTrue("screenshot.png" in result.files, "with the screenshot")
        assertContains(result.files.getValue("events.txt").decodeToString(), "Connected (simulator)")

        onNodeWithTag("report-github").performClick()
        assertEquals(listOf(ReportIssue.url(report)), opened)
        onNodeWithTag("report-done").performClick()
        waitForIdle()
        onNodeWithTag("report-dialog").assertDoesNotExist()
    }

    @Test
    fun theScreenshotCanBeLeftOut() = runDesktopComposeUiTest(1340, 800) {
        setContent { App(listOf(SimulatorTransport()), reports = support) }
        waitForIdle()
        onNodeWithTag("report-button").performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("report-screenshot").assertExists() }.isSuccess }
        onNodeWithTag("report-screenshot").performClick()
        onNodeWithTag("report-save").performClick()
        waitUntil(timeoutMillis = 5_000) { saved != null }
        val result = ReportCheck.check(assertNotNull(saved).second)
        assertTrue(result.ok, result.problems.toString())
        assertTrue("screenshot.png" !in result.files)
    }

    @Test
    fun reportFromSetupClosesSetupFirst() = runDesktopComposeUiTest(1340, 800) {
        setContent { App(listOf(SimulatorTransport()), reports = support) }
        waitForIdle()
        onNodeWithTag("setup-button").performClick()
        onNodeWithTag("setup-report").performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("report-dialog").assertExists() }.isSuccess }
        onNodeWithTag("setup-panel").assertDoesNotExist()
    }

    @Test
    fun withoutReportSupportThereIsNoReportButton() = runDesktopComposeUiTest(1340, 800) {
        setContent { App(listOf(SimulatorTransport())) }
        waitForIdle()
        onNodeWithTag("report-button").assertDoesNotExist()
    }
}

package io.github.l6desk.report

import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.MidiDeviceInfo
import io.github.l6desk.midi.MidiLogEntry
import io.github.l6desk.midi.MidiMessage
import io.github.l6desk.model.Profile
import io.github.l6desk.net.BridgeAddress
import io.github.l6desk.net.BridgeSession
import io.github.l6desk.net.MixerStatus
import io.github.l6desk.persistence.AppSettings
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReportPackageTest {
    private val dir: File = Files.createTempDirectory("report-key").toFile().also { it.deleteOnExit() }
    private val key = FileReportKey(File(dir, FileReportKey.FILE_NAME))
    private val install = ReportPackage.fingerprint(key.publicKey)

    private val onPi = ReportContext(
        connection = ConnectionState.Connected(
            MidiDeviceInfo("bridge:192.168.1.166:5704", "L6Desk Bridge (bridge 192.168.1.166) → L6max", MidiDeviceInfo.Kind.NETWORK),
        ),
        bridge = BridgeSession("L6Desk Bridge", BridgeAddress("192.168.1.166"), 4, MixerStatus(true, "L6max")),
        canPowerOff = true,
        player = null,
        profile = Profile.FACTORY,
        settings = AppSettings(bridges = listOf("192.168.1.166")),
        platform = mapOf("os" to "Linux", "osVersion" to "6.8", "distribution" to "Ubuntu 24.04 LTS"),
        screen = "1340×800 px, density 1.0",
        bridgeInfo = io.github.l6desk.net.BridgeInfo(
            "pi", "0.14.0", board = "Raspberry Pi Zero 2 W Rev 1.0", underVoltageNow = false, underVoltageCount = 2, temperatureC = 47.2,
        ),
    )

    private fun report(message: String = "The faders froze after the Pi reconnected.") =
        buildReport("ABCD2345", "2026-10-11T10:00:00Z", message, onPi, install)

    private fun png(width: Int = 40, height: Int = 30): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    private fun good(screenshot: ByteArray? = png()) = ReportPackage.build(report(), "2026-10-11T09:59:58Z  Connected\n", screenshot, key)

    private fun entries(zip: ByteArray): List<Pair<String, ByteArray>> = buildList {
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                add(e.name to z.readBytes())
            }
        }
    }

    private fun rezip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun aReportFromTheAppPassesWithItsInstallFingerprint() {
        val result = ReportCheck.check(good())
        assertTrue(result.ok, result.problems.toString())
        assertEquals(install, result.fingerprint)
        assertEquals("ABCD2345", result.report?.id)
        assertEquals(setOf("report.json", "events.txt", "screenshot.png", "manifest.json", "manifest.sig"), result.files.keys)
        assertTrue(ReportCheck.check(good(screenshot = null)).ok, "the screenshot is optional")
    }

    @Test
    fun theInstallKeyIsKeptAndReused() {
        val again = FileReportKey(File(dir, FileReportKey.FILE_NAME))
        assertTrue(again.publicKey.contentEquals(key.publicKey))
        val other = FileReportKey(File(Files.createTempDirectory("other").toFile(), FileReportKey.FILE_NAME))
        assertNotEquals(install, ReportPackage.fingerprint(other.publicKey))
        assertTrue(Regex("""[0-9a-f]{4}(-[0-9a-f]{4}){3}""").matches(install), install)
    }

    @Test
    fun anUnreadableKeyFileIsReplacedByANewKey() {
        val file = File(Files.createTempDirectory("broken").toFile(), FileReportKey.FILE_NAME)
        file.writeText("public=nonsense\nprivate=???\n")
        val replaced = FileReportKey(file)
        assertTrue(ReportCheck.check(ReportPackage.build(report().copy(install = ReportPackage.fingerprint(replaced.publicKey)), "", null, replaced)).ok)
        assertTrue(FileReportKey(file).publicKey.contentEquals(replaced.publicKey), "and saved")
    }

    @Test
    fun aChangedFileIsCaught() {
        val tampered = entries(good()).map { (name, bytes) ->
            name to if (name == "report.json") bytes.decodeToString().replace("faders froze", "run this script").encodeToByteArray() else bytes
        }
        val result = ReportCheck.check(rezip(tampered))
        assertFalse(result.ok)
        assertContains(result.problems.joinToString(), "report.json has been changed")
        assertTrue(result.files.isEmpty(), "nothing is handed out from a failed report")
    }

    @Test
    fun aManifestResignedWithAnotherKeyIsCaughtByTheInstallName() {
        // Someone edits the report and signs it all again with their own key: the signature is
        // valid, but report.json still names the original install.
        val forger = FileReportKey(File(Files.createTempDirectory("forger").toFile(), FileReportKey.FILE_NAME))
        val result = ReportCheck.check(ReportPackage.build(report("edited"), "", null, forger))
        assertFalse(result.ok)
        assertContains(result.problems.joinToString(), "but it was signed by")
    }

    @Test
    fun aBrokenSignatureIsCaught() {
        val broken = entries(good()).map { (name, bytes) ->
            name to if (name == "manifest.json") bytes.decodeToString().replace("ABCD2345", "ZZZZ2345").encodeToByteArray() else bytes
        }
        assertEquals(listOf("the signature doesn't match manifest.json"), ReportCheck.check(rezip(broken)).problems)
    }

    @Test
    fun unexpectedOrRepeatedEntriesAreRejectedUnopened() {
        val base = entries(good())
        for (extra in listOf("../../.bashrc", "run.sh", "screenshots/", "report.json ")) {
            val result = ReportCheck.check(rezip(base + (extra to ByteArray(3))))
            assertFalse(result.ok, extra)
            assertContains(result.problems.single(), "unexpected entry")
        }
        // ZipOutputStream won't write a name twice, so write another name and rename it in the bytes.
        val twice = rezip(base + ("report.jsoZ" to ByteArray(3))).let { zip ->
            val text = String(zip, Charsets.ISO_8859_1).replace("report.jsoZ", "report.json")
            text.toByteArray(Charsets.ISO_8859_1)
        }
        assertContains(ReportCheck.check(twice).problems.single(), "appears twice")
        assertContains(ReportCheck.check(rezip(base.filterNot { it.first == "events.txt" })).problems.single(), "events.txt is missing")
    }

    @Test
    fun aZipBombStopsAtTheLimit() {
        val bomb = rezip(entries(good()).filterNot { it.first == "screenshot.png" } + ("screenshot.png" to ByteArray(50 * 1024 * 1024)))
        assertTrue(bomb.size < 200_000, "compresses to ${bomb.size} bytes")
        assertContains(ReportCheck.check(bomb).problems.single(), "larger than")
    }

    @Test
    fun notAZipAtAll() {
        assertFalse(ReportCheck.check("hello".encodeToByteArray()).ok)
        assertFalse(ReportCheck.check(ByteArray(0)).ok)
    }

    @Test
    fun aFakeOrGiganticScreenshotIsCaught() {
        fun withShot(bytes: ByteArray) = ReportCheck.check(ReportPackage.build(report(), "", bytes, key))
        assertContains(withShot("<script>".encodeToByteArray()).problems.single(), "not a PNG")
        // A real header claiming 100000×100000 pixels: never decoded.
        val huge = png().copyOf().also { p -> listOf(16, 20).forEach { at -> p[at] = 0; p[at + 1] = 1; p[at + 2] = 0x86.toByte(); p[at + 3] = 0xA0.toByte() } }
        assertContains(withShot(huge).problems.single(), "unexpected size")
        assertContains(withShot(png().copyOf(60)).problems.single(), "can't be decoded")
    }

    @Test
    fun terminalEscapesInTheEventsAreCaught() {
        val result = ReportCheck.check(ReportPackage.build(report(), "ok\u001b[2Jcleared", null, key))
        assertContains(result.problems.single(), "control character")
    }

    @Test
    fun theReportCarriesNoHomeAddresses() {
        val text = String(entries(good()).first { it.first == "report.json" }.second)
        assertFalse("192.168" in text, text)
        assertContains(text, "a private network")
        assertContains(text, "<private address>")
        assertContains(text, "\"route\": \"Wi-Fi bridge\"")
        assertContains(text, "\"canPowerOff\": true")
        assertContains(text, "\"underVoltageCount\": 2")
        assertContains(text, "Raspberry Pi Zero 2 W")
        assertEquals("the Pi's hotspot (10.42.0.1) ok, <private address>, <address>, 127.0.0.1", maskAddresses("the Pi's hotspot (10.42.0.1) ok, 192.168.0.4, 8.8.8.8, 127.0.0.1"))
    }

    @Test
    fun aChangedCcMapIsIncludedTheFactoryOneIsnt() {
        assertEquals(null, report().profile.ccMap)
        val custom = Profile("Mine", Profile.FACTORY.ccMap.withPadNote(1, 70))
        val r = buildReport("ABCD2345", "t", "", ReportContext(ConnectionState.Disconnected, null, false, null, custom, AppSettings(), emptyMap(), null), install)
        assertEquals(70, r.profile.padNotes?.first())
        assertEquals(Profile.FACTORY.ccMap.assignments.size, r.profile.ccMap?.size)
        assertEquals("none", r.connection.route)
    }

    @Test
    fun eventsListAppEventsThenMidi() {
        val text = eventsText(
            listOf(AppEvent("2026-10-11T10:00:00Z", "Connected to Bridge 192.168.1.166")),
            listOf(MidiLogEntry(true, MidiMessage.ControlChange(1, 7, 100), 1_234)),
            Profile.FACTORY,
        )
        assertContains(text, "2026-10-11T10:00:00Z  Connected to Bridge <private address>")
        assertContains(text, "1.234  OUT  B0 07 64")
    }

    @Test
    fun theIssueLinkCarriesTitleAndBody() {
        val r = report("Line one is the summary\nmore detail & 100% sure")
        assertEquals("[report ABCD2345] Line one is the summary", ReportIssue.title(r))
        val url = ReportIssue.url(r)
        assertTrue(url.startsWith("https://github.com/l6desk/l6desk/issues/new?title=%5Breport%20ABCD2345%5D%20Line"), url)
        assertFalse(' ' in url || '\n' in url || '&' in url.substringAfter("&body="), "encoded")
        assertContains(ReportIssue.body(r), "l6desk-report-ABCD2345.zip")
        assertContains(ReportIssue.body(r), install)
        assertTrue(ReportIssue.url(report("x".repeat(4_000))).length < 8_000)
        assertEquals("%C3%A9%20a%2Bb", ReportIssue.encode("é a+b"))
    }

    @Test
    fun idsAreReadable() {
        repeat(20) { assertTrue(Regex("[A-HJ-NP-Z2-9]{8}").matches(ReportPackage.newId())) }
    }
}

package io.github.l6desk.bridge

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BridgeConfigTest {
    @Test
    fun parsesTheConfigFile() {
        val warnings = mutableListOf<String>()
        val c = BridgeConfig.parseFile(
            """
            # L6max bridge
            port = 5705
            name = Village hall   # trailing comment
            led=none
            log_midi=true
            power_off=false
            colour=blue
            port=nope
            """.trimIndent(),
            warn = { warnings += it },
        )
        assertEquals(BridgeConfig(port = 5705, name = "Village hall", led = "none", logMidi = true, powerOff = false), c)
        assertEquals(2, warnings.size, "unknown key and bad value are reported")
    }

    @Test
    fun flagsOverrideTheFile() {
        val f = Files.createTempFile("bridge", ".conf").toFile().apply { writeText("port=6000\nname=From file\n") }
        val c = BridgeConfig.load(arrayOf("--config", f.path, "--port", "6001", "--no-led", "--simulator"))
        assertEquals(BridgeConfig(port = 6001, name = "From file", led = "none", simulator = true), c)
        f.delete()
    }

    @Test
    fun powerOffIsOnByDefaultAndCanBeTurnedOff() {
        assertEquals(true, BridgeConfig().powerOff)
        val f = Files.createTempFile("bridge", ".conf").toFile()
        assertEquals(false, BridgeConfig.load(arrayOf("--config", f.path, "--no-power-off"))?.powerOff)
        f.delete()
    }

    @Test
    fun powerOffWritesTheRequestOnlyWhereItsFolderExists() {
        val dir = Files.createTempDirectory("l6desk-run").toFile()
        val request = dir.resolve("power-off")
        val power = PowerOff(request)
        assertEquals(true, power.available)
        assertEquals(true, power.request())
        assertEquals(true, request.isFile, "the request the poweroff path unit waits for")
        assertEquals(false, PowerOff(dir.resolve("missing/power-off")).available, "not run by the service: not offered")
        dir.deleteRecursively()
    }

    @Test
    fun aPinIsFourToEightDigitsAndEmptyMeansNone() {
        assertEquals("", BridgeConfig().pin)
        assertEquals("2468", BridgeConfig.parseFile("pin=2468").pin)
        assertEquals("", BridgeConfig.parseFile("pin=").pin)
        val warnings = mutableListOf<String>()
        assertEquals("", BridgeConfig.parseFile("pin=12ab", warn = { warnings += it }).pin, "not a PIN: ignored, and said so")
        assertEquals(1, warnings.size)
        val f = Files.createTempFile("bridge", ".conf").toFile().apply { writeText("pin=1111\n") }
        assertEquals("13579", BridgeConfig.load(arrayOf("--config", f.path, "--pin", "13579"))?.pin, "the flag wins")
        kotlin.test.assertFailsWith<IllegalArgumentException> { BridgeConfig.load(arrayOf("--config", f.path, "--pin", "12")) }
        f.delete()
    }

    @Test
    fun helpAndBadFlags() {
        assertNull(BridgeConfig.load(arrayOf("--help")))
        assertFailsWith<IllegalArgumentException> { BridgeConfig.load(arrayOf("--port", "99999")) }
        assertFailsWith<IllegalArgumentException> { BridgeConfig.load(arrayOf("--bogus")) }
        assertFailsWith<IllegalArgumentException> { BridgeConfig.load(arrayOf("--config", "/nonexistent.conf")) }
    }

    @Test
    fun ledPatterns() {
        assertEquals(LedPattern.WAITING_FOR_MIXER, LedPattern.of(mixerConnected = false, tabletConnected = false, error = false))
        assertEquals(LedPattern.WAITING_FOR_TABLET, LedPattern.of(mixerConnected = true, tabletConnected = false, error = false))
        assertEquals(LedPattern.TABLET_CONNECTED, LedPattern.of(mixerConnected = true, tabletConnected = true, error = false))
        assertEquals(LedPattern.TABLET_CONNECTED, LedPattern.of(mixerConnected = false, tabletConnected = true, error = false))
        assertEquals(LedPattern.ERROR, LedPattern.of(mixerConnected = true, tabletConnected = true, error = true))
    }

    @Test
    fun ledFallsBackQuietlyWhenAbsent() {
        val logs = mutableListOf<String>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        assertNull(StatusLed.open("none", scope) { logs += it })
        assertNull(StatusLed.open("/sys/class/leds/does-not-exist", scope) { logs += it })
        assertEquals(1, logs.size)
    }

    @Test
    fun drivesAnLedThroughItsBrightnessFile() {
        val dir = Files.createTempDirectory("led").toFile()
        val brightness = dir.resolve("brightness").apply { writeText("0") }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val led = requireNotNull(StatusLed.open(dir.path, scope) {})
        led.show(LedPattern.TABLET_CONNECTED)
        Thread.sleep(200)
        assertEquals("1", brightness.readText())
        led.off()
        Thread.sleep(50)
        assertEquals("0", brightness.readText())
        dir.deleteRecursively()
    }
}

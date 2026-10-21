package io.github.l6desk.net

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BridgeHealth against a fake /sys and /proc laid out like the Pi Zero 2 W's. */
class BridgeHealthTest {
    private val root: File = Files.createTempDirectory("pi-root").toFile().also { it.deleteOnExit() }
    private fun file(path: String, text: String) = File(root, path).apply { parentFile.mkdirs(); writeText(text) }
    private var clock = Instant.parse("2026-10-12T09:00:00Z")

    private fun pi(): BridgeHealth {
        file("sys/class/hwmon/hwmon0/name", "cpu_thermal\n")
        file("sys/class/hwmon/hwmon1/name", "rpi_volt\n")
        file("sys/class/hwmon/hwmon1/in0_lcrit_alarm", "0\n")
        file("sys/class/thermal/thermal_zone0/temp", "46160\n")
        file("proc/device-tree/model", "Raspberry Pi Zero 2 W Rev 1.0\u0000")
        file("proc/uptime", "71967.20 286529.62\n")
        file("etc/os-release", "PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\nNAME=\"Debian\"\n")
        return BridgeHealth(BridgeInfo.KIND_PI, root = root, now = { clock })
    }

    @Test
    fun describesThePi() {
        val info = pi().describe()
        assertEquals("Raspberry Pi Zero 2 W Rev 1.0", info.board)
        assertEquals("Debian GNU/Linux 13 (trixie)", info.os)
        assertEquals(71967L, info.uptimeSeconds)
        assertEquals(46.1, info.temperatureC)
        assertEquals(false, info.underVoltageNow)
        assertEquals(0, info.underVoltageCount)
        assertTrue(info.warnings.isEmpty())
    }

    @Test
    fun anUnderVoltageDipIsRememberedAfterItEnds() {
        val health = pi()
        assertFalse(health.sample())
        file("sys/class/hwmon/hwmon1/in0_lcrit_alarm", "1\n")
        clock = Instant.parse("2026-10-12T21:15:00Z")
        assertTrue(health.sample(), "the alarm went on")
        assertFalse(health.sample(), "still on: not a new dip")
        assertEquals(listOf("The Pi's power supply is too weak right now (under-voltage)"), health.warnings.value)
        file("sys/class/hwmon/hwmon1/in0_lcrit_alarm", "0\n")
        health.sample()
        val info = health.describe()
        assertEquals(false, info.underVoltageNow)
        assertEquals(1, info.underVoltageCount)
        assertEquals("2026-10-12T21:15:00Z", info.underVoltageLast)
        assertEquals(listOf("The Pi's power supply dipped too low 1 time(s) (under-voltage)"), health.warnings.value)
    }

    @Test
    fun theHighestTemperatureIsKept() {
        val health = pi()
        file("sys/class/thermal/thermal_zone0/temp", "81234\n")
        health.sample()
        file("sys/class/thermal/thermal_zone0/temp", "60000\n")
        health.sample()
        val info = health.describe()
        assertEquals(60.0, info.temperatureC)
        assertEquals(81.2, info.maxTemperatureC)
    }

    @Test
    fun aComputerWithoutThePisSensorsLeavesThemUnknown() {
        val info = BridgeHealth(BridgeInfo.KIND_DESKTOP, root = root).describe()
        assertNull(info.underVoltageNow)
        assertNull(info.underVoltageCount)
        assertNull(info.temperatureC)
        assertNull(info.board)
        assertEquals(BridgeInfo.KIND_DESKTOP, info.kind)
        assertTrue(info.os.isNotBlank())
    }
}

package io.github.l6desk.net

import io.github.l6desk.AppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Describes the computer a bridge runs on, for [BridgeInfo] (protocol v5). On a Raspberry Pi it
 * watches the under-voltage alarm and the temperature every [interval] ([start]), because the
 * alarm only says "now": a dip mid-song would otherwise be gone by the time anyone asks.
 *
 * Everything is read from world-readable files (`/sys`, `/proc`), so the bridge service needs no
 * extra rights (`vcgencmd` would: /dev/vcio is root's). Files that don't exist (a desktop, another
 * board) leave their fields null. [root] is "/" except in tests.
 */
class BridgeHealth(
    private val kind: String,
    private val musicDir: File? = null,
    private val root: File = File("/"),
    private val interval: Duration = 2.seconds,
    private val now: () -> Instant = Instant::now,
) {
    private val started = now()

    /** The current warnings (see [BridgeInfo.warnings]); changes when one appears or clears. */
    private val _warnings = MutableStateFlow<List<String>>(emptyList())
    val warnings: StateFlow<List<String>> = _warnings.asStateFlow()

    private var underVoltageNow: Boolean? = null
    private var underVoltageCount = 0
    private var underVoltageLast: Instant? = null
    private var maxTemperature: Double? = null

    /** Watches the Pi's sensors until [scope] ends; [onUnderVoltage] is told when the alarm goes on. */
    fun start(scope: CoroutineScope, onUnderVoltage: () -> Unit = {}) {
        if (underVoltageFile() == null && temperatureFile() == null) return
        scope.launch {
            while (isActive) {
                if (sample()) onUnderVoltage()
                delay(interval)
            }
        }
    }

    /** Reads the sensors once; true if the under-voltage alarm has just gone on. */
    @Synchronized
    fun sample(): Boolean {
        val alarm = underVoltageFile()?.let { read(it)?.trim() == "1" }
        val wentOn = alarm == true && underVoltageNow != true
        if (wentOn) {
            underVoltageCount++
            underVoltageLast = now()
        }
        underVoltageNow = alarm
        temperature()?.let { t -> maxTemperature = maxOf(maxTemperature ?: t, t) }
        _warnings.value = describe().warnings
        return wentOn
    }

    @Synchronized
    fun describe(): BridgeInfo {
        val temperature = temperature()
        val hasAlarm = underVoltageFile() != null
        return BridgeInfo(
            kind = kind,
            version = AppInfo.VERSION,
            os = osName(),
            java = "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})",
            board = read(File(root, "proc/device-tree/model"))?.trim('\u0000', ' ', '\n')?.takeIf { it.isNotEmpty() },
            uptimeSeconds = read(File(root, "proc/uptime"))?.substringBefore(' ')?.toDoubleOrNull()?.toLong(),
            bridgeUptimeSeconds = (now().epochSecond - started.epochSecond),
            underVoltageNow = if (hasAlarm) underVoltageNow ?: (underVoltageFile()?.let { read(it)?.trim() == "1" }) else null,
            underVoltageCount = if (hasAlarm) underVoltageCount else null,
            underVoltageLast = underVoltageLast?.toString(),
            temperatureC = temperature,
            maxTemperatureC = maxTemperature?.let { maxOf(it, temperature ?: it) } ?: temperature,
            musicFreeMb = musicDir?.takeIf { it.isDirectory }?.usableSpace?.let { it / (1024 * 1024) },
        )
    }

    /** The rpi_volt hwmon's alarm file ("1" while the supply is too low), if this is a Pi. */
    private fun underVoltageFile(): File? =
        File(root, "sys/class/hwmon").listFiles()?.sortedBy { it.name }
            ?.firstOrNull { read(File(it, "name"))?.trim() == "rpi_volt" }
            ?.let { File(it, "in0_lcrit_alarm") }?.takeIf { it.isFile }

    private fun temperatureFile(): File? = File(root, "sys/class/thermal/thermal_zone0/temp").takeIf { it.isFile }

    /** °C, to one decimal place. */
    private fun temperature(): Double? = temperatureFile()?.let { read(it) }?.trim()?.toLongOrNull()?.let { it / 100 / 10.0 }

    private fun osName(): String {
        val pretty = read(File(root, "etc/os-release"))?.lineSequence()?.firstOrNull { it.startsWith("PRETTY_NAME=") }
            ?.substringAfter('=')?.trim('"')
        return pretty ?: "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
    }

    private fun read(file: File): String? = runCatching { file.readText() }.getOrNull()
}

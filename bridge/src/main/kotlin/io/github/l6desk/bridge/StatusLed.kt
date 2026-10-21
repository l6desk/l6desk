package io.github.l6desk.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** What the status LED shows (there's no screen on the bridge). */
enum class LedPattern(val onMs: Long, val offMs: Long) {
    /** No L6max on USB: a short blink every second. */
    WAITING_FOR_MIXER(100, 900),

    /** L6max connected, no tablet: fast blinking. */
    WAITING_FOR_TABLET(250, 250),

    /** A tablet is in control: steady on. */
    TABLET_CONNECTED(1, 0),

    /** Something is wrong (e.g. the port is in use): rapid flicker. */
    ERROR(60, 60),
    ;

    companion object {
        fun of(mixerConnected: Boolean, tabletConnected: Boolean, error: Boolean) = when {
            error -> ERROR
            tabletConnected -> TABLET_CONNECTED
            mixerConnected -> WAITING_FOR_TABLET
            else -> WAITING_FOR_MIXER
        }
    }
}

/**
 * Drives a Linux LED via sysfs (`/sys/class/leds/<led>/brightness`). The systemd unit sets the
 * LED's trigger to "none" and makes `brightness` writable before the bridge starts.
 */
class StatusLed private constructor(private val brightness: File, private val scope: CoroutineScope) {
    private var job: Job? = null
    var pattern: LedPattern? = null
        private set

    fun show(p: LedPattern) {
        if (p == pattern) return
        pattern = p
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            if (p.offMs == 0L) {
                write(true)
                return@launch
            }
            while (isActive) {
                write(true)
                delay(p.onMs)
                write(false)
                delay(p.offMs)
            }
        }
    }

    fun off() {
        job?.cancel()
        write(false)
    }

    private fun write(on: Boolean) {
        runCatching { brightness.writeText(if (on) "1" else "0") }
    }

    companion object {
        private val AUTO = listOf("/sys/class/leds/ACT", "/sys/class/leds/led0")

        /** The LED for [setting] ("auto", "none" or a directory), or null if unavailable. */
        fun open(setting: String, scope: CoroutineScope, log: (String) -> Unit): StatusLed? {
            if (setting == "none") return null
            val candidates = if (setting == "auto") AUTO else listOf(setting)
            val dir = candidates.map(::File).firstOrNull { File(it, "brightness").exists() }
            if (dir == null) {
                if (setting != "auto") log("LED $setting not found; running without a status LED")
                return null
            }
            val brightness = File(dir, "brightness")
            if (!brightness.canWrite()) {
                log("LED ${dir.path} isn't writable (the systemd unit normally arranges this); running without it")
                return null
            }
            log("Status LED: ${dir.path}")
            return StatusLed(brightness, scope)
        }
    }
}

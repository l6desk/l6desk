package io.github.l6desk.bridge

import io.github.l6desk.net.BridgePin
import io.github.l6desk.net.BridgeProtocol
import java.io.File

/** Bridge settings: from /etc/l6desk-bridge.conf (key=value lines), overridden by command-line flags. */
data class BridgeConfig(
    val port: Int = BridgeProtocol.DEFAULT_PORT,
    val name: String = "L6Desk Bridge",
    /** "auto" (the board's activity LED), "none", or a /sys/class/leds/<led> directory. */
    val led: String = "auto",
    /** Log every MIDI message (noisy; for fault-finding). */
    val logMidi: Boolean = false,
    /** Use the built-in L6max simulator instead of USB (testing without the mixer). */
    val simulator: Boolean = false,
    /** The music player's folder of WAV files; empty turns the player off. */
    val music: String = DEFAULT_MUSIC,
    /** The app may power the Pi off (e.g. when it closes); see [PowerOff]. */
    val powerOff: Boolean = true,
    /** Apps must give this PIN (4 to 8 digits) to connect; empty for none. */
    val pin: String = "",
) {
    companion object {
        const val DEFAULT_PATH = "/etc/l6desk-bridge.conf"
        const val DEFAULT_MUSIC = "/srv/l6max-music"

        const val USAGE = """Usage: l6desk-bridge [--config FILE] [--port N] [--name NAME] [--led auto|none|/sys/class/leds/X]
                    [--music DIR] [--no-power-off] [--pin DIGITS] [--log-midi] [--simulator] [--help]
Settings come from $DEFAULT_PATH (if present), then these flags."""

        /** Parses `key=value` lines; `#` starts a comment. Unknown keys and bad values are reported. */
        fun parseFile(text: String, base: BridgeConfig = BridgeConfig(), warn: (String) -> Unit = {}): BridgeConfig {
            var c = base
            text.lineSequence().forEachIndexed { i, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val key = line.substringBefore('=').trim().lowercase()
                val value = line.substringAfter('=', "").trim()
                c = when (key) {
                    "port" -> value.toIntOrNull()?.takeIf { it in 1..65535 }?.let { c.copy(port = it) }
                    "name" -> value.takeIf { it.isNotEmpty() }?.let { c.copy(name = it.take(60)) }
                    "led" -> value.takeIf { it.isNotEmpty() }?.let { c.copy(led = it) }
                    "log_midi" -> value.toBooleanStrictOrNull()?.let { c.copy(logMidi = it) }
                    "simulator" -> value.toBooleanStrictOrNull()?.let { c.copy(simulator = it) }
                    "music" -> c.copy(music = value)
                    "power_off" -> value.toBooleanStrictOrNull()?.let { c.copy(powerOff = it) }
                    "pin" -> value.takeIf { it.isEmpty() || BridgePin.isValid(it) }?.let { c.copy(pin = it) }
                    else -> null
                } ?: c.also { warn("Config line ${i + 1} ignored: \"$raw\"") }
            }
            return c
        }

        /**
         * The full configuration: the file named by --config (or the default, if it exists), then
         * flags. Returns null for --help. Throws IllegalArgumentException on a bad flag.
         */
        fun load(args: Array<String>, warn: (String) -> Unit = {}): BridgeConfig? {
            if ("--help" in args || "-h" in args) return null
            val configPath = args.indexOf("--config").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) ?: throw IllegalArgumentException("--config needs a file") }
            val file = File(configPath ?: DEFAULT_PATH)
            var c = BridgeConfig()
            if (file.isFile) c = parseFile(file.readText(), c, warn)
            else if (configPath != null) throw IllegalArgumentException("Config file not found: $configPath")

            var i = 0
            fun value(flag: String) = args.getOrNull(++i) ?: throw IllegalArgumentException("$flag needs a value")
            while (i < args.size) {
                when (val a = args[i]) {
                    "--config" -> i++
                    "--port" -> c = c.copy(port = value(a).toIntOrNull()?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("Bad port"))
                    "--name" -> c = c.copy(name = value(a))
                    "--led" -> c = c.copy(led = value(a))
                    "--no-led" -> c = c.copy(led = "none")
                    "--log-midi" -> c = c.copy(logMidi = true)
                    "--simulator" -> c = c.copy(simulator = true)
                    "--music" -> c = c.copy(music = value(a))
                    "--no-power-off" -> c = c.copy(powerOff = false)
                    "--pin" -> c = c.copy(pin = value(a).takeIf(BridgePin::isValid) ?: throw IllegalArgumentException("A PIN is 4 to 8 digits"))
                    else -> throw IllegalArgumentException("Unknown option: $a")
                }
                i++
            }
            return c
        }
    }
}

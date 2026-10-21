package io.github.l6desk.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a bridge says about itself (protocol v5, [Frame.BridgeInfo]): what it runs on and how its
 * computer is doing. Sent after [Frame.Welcome], again whenever a warning appears or clears, and
 * when the app asks ([Frame.BridgeInfoRequest], e.g. for a problem report). Fields a bridge can't
 * tell are null.
 */
@Serializable
data class BridgeInfo(
    /** [KIND_PI] (the headless bridge) or [KIND_DESKTOP] (the desktop app's Bridge mode). */
    val kind: String,
    /** The bridge's L6Desk version. */
    val version: String,
    val os: String = "",
    val java: String = "",
    /** E.g. "Raspberry Pi Zero 2 W Rev 1.0". */
    val board: String? = null,
    val uptimeSeconds: Long? = null,
    val bridgeUptimeSeconds: Long? = null,
    /** The Pi's under-voltage alarm: its power supply is too weak right now. */
    val underVoltageNow: Boolean? = null,
    /** Times the alarm went on since the bridge started, and when it last did (ISO 8601, UTC). */
    val underVoltageCount: Int? = null,
    val underVoltageLast: String? = null,
    val temperatureC: Double? = null,
    /** The highest temperature since the bridge started. */
    val maxTemperatureC: Double? = null,
    /** Free space where the music player keeps its songs. */
    val musicFreeMb: Long? = null,
) {
    /** Problems worth telling the user about, in plain words; empty when all is well. */
    val warnings: List<String>
        get() = buildList {
            val pi = if (kind == KIND_PI) "The Pi" else "The bridge's computer"
            if (underVoltageNow == true) add("$pi's power supply is too weak right now (under-voltage)")
            else if ((underVoltageCount ?: 0) > 0) add("$pi's power supply dipped too low $underVoltageCount time(s) (under-voltage)")
            if ((temperatureC ?: 0.0) >= HOT_C) add("$pi is hot (${temperatureC?.let { formatC(it) }})")
            if (musicFreeMb != null && musicFreeMb < LOW_SPACE_MB) add("$pi has little space left for songs (${musicFreeMb} MB)")
        }

    companion object {
        const val KIND_PI = "pi"
        const val KIND_DESKTOP = "desktop"

        /** The Pi slows itself down from about here (and at 85 °C, hard). */
        const val HOT_C = 80.0
        const val LOW_SPACE_MB = 200L

        private val json = Json {
            ignoreUnknownKeys = true // from a newer bridge
            encodeDefaults = false
            explicitNulls = false
        }

        fun encode(info: BridgeInfo): String = json.encodeToString(serializer(), info)

        fun decode(text: String): BridgeInfo = json.decodeFromString(serializer(), text)

        private fun formatC(c: Double) = "${(c * 10).toLong() / 10.0} °C"
    }
}

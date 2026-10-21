package io.github.l6desk.report

import io.github.l6desk.AppInfo
import io.github.l6desk.midi.CcCodec
import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.MidiDeviceInfo
import io.github.l6desk.midi.MidiLogEntry
import io.github.l6desk.midi.toHex
import io.github.l6desk.model.Control
import io.github.l6desk.model.Profile
import io.github.l6desk.net.BridgeInfo
import io.github.l6desk.net.BridgeSession
import io.github.l6desk.net.RemotePlayer
import io.github.l6desk.persistence.AppSettings
import kotlinx.serialization.Serializable

/**
 * `report.json` in a problem report zip (see docs/report-issue-plan.md): what the user wrote, and
 * what the app knew when they pressed REPORT. Nothing personal: no Wi-Fi names, no home addresses
 * (see [maskAddresses]), no file paths.
 */
@Serializable
data class ProblemReport(
    val format: Int = FORMAT,
    val id: String,
    val created: String,
    val message: String,
    val app: AppDetails,
    /** "os" (Android, Linux, Windows…), its version, the device or distribution, the Java runtime. */
    val platform: Map<String, String>,
    /** The app window's size, e.g. "1340×800 px, density 1.33". */
    val screen: String? = null,
    val connection: ConnectionDetails,
    val bridge: BridgeDetails? = null,
    val profile: ProfileDetails,
    val settings: Map<String, String> = emptyMap(),
    /** The fingerprint of the install's report key (see [ReportPackage.fingerprint]). */
    val install: String,
    /** Extra facts from the platform, e.g. every MIDI device Java Sound sees (desktop). */
    val diagnostics: Map<String, String> = emptyMap(),
) {
    companion object {
        const val FORMAT = 1
        const val MAX_MESSAGE = 4_000
    }
}

@Serializable
data class AppDetails(val name: String, val version: String)

@Serializable
data class ConnectionDetails(
    /** USB cable, Bluetooth, Wi-Fi bridge, Simulator, or none. */
    val route: String,
    /** Connected, Connecting, Error or Disconnected. */
    val state: String,
    val device: String? = null,
    val error: String? = null,
)

@Serializable
data class BridgeDetails(
    val name: String,
    /** "the Pi's hotspot (10.42.0.1)", "a private network", "this computer"… never a home address. */
    val address: String,
    val protocol: Int,
    /** The mixer connected at the bridge, or null if none. */
    val mixer: String?,
    /** Only a Pi bridge with power_off=true offers it; a desktop in Bridge mode never does. */
    val canPowerOff: Boolean,
    val hasPlayer: Boolean,
    val player: String? = null,
    /** What the bridge says about itself and its computer (v5 bridges): versions, under-voltage, temperature. */
    val info: BridgeInfo? = null,
)

@Serializable
data class ProfileDetails(
    val name: String,
    val factory: Boolean,
    val midiChannel: Int,
    /** The whole CC map, only when it differs from the factory map. */
    val ccMap: Map<String, Int>? = null,
    val padNotes: List<Int?>? = null,
)

/** What [buildReport] needs to know about the app at the moment REPORT was pressed. */
class ReportContext(
    val connection: ConnectionState,
    val bridge: BridgeSession?,
    val canPowerOff: Boolean,
    val player: RemotePlayer?,
    val profile: Profile,
    val settings: AppSettings,
    val platform: Map<String, String>,
    val screen: String?,
    /** Fresh from the bridge (v5), asked for when REPORT was pressed. */
    val bridgeInfo: BridgeInfo? = null,
    val diagnostics: Map<String, String> = emptyMap(),
)

fun buildReport(id: String, created: String, message: String, context: ReportContext, install: String): ProblemReport {
    val c = context.connection
    val device = when (c) {
        is ConnectionState.Connected -> c.device
        is ConnectionState.Connecting -> c.device
        else -> null
    }
    val connection = ConnectionDetails(
        route = device?.let { routeOf(it.kind) } ?: "none",
        state = when (c) {
            is ConnectionState.Connected -> "Connected"
            is ConnectionState.Connecting -> "Connecting"
            is ConnectionState.Error -> "Error"
            ConnectionState.Disconnected -> "Disconnected"
        },
        device = device?.name?.let(::maskAddresses),
        error = (c as? ConnectionState.Error)?.reason?.let(::maskAddresses),
    )
    val bridge = context.bridge?.let { b ->
        BridgeDetails(
            name = b.name,
            address = describeAddress(b.address.host),
            protocol = b.protocol,
            mixer = b.mixer.takeIf { it.connected }?.name,
            canPowerOff = context.canPowerOff,
            hasPlayer = context.player != null,
            player = context.player?.state?.let { s ->
                listOfNotNull(
                    if (s.ready) "USB audio ready" else "USB audio not ready",
                    if (s.playing != null) (if (s.paused) "paused" else "playing") else "stopped",
                    "output ${s.output}",
                    "volume ${s.volume}",
                    "${context.player.tracks.size} songs in ${s.playlists.size} playlists",
                    s.error?.let { "error: $it" },
                ).joinToString(", ")
            },
            info = context.bridgeInfo,
        )
    }
    val profile = context.profile
    val factory = profile.ccMap == Profile.FACTORY.ccMap
    val settings = context.settings
    return ProblemReport(
        id = id,
        created = created,
        message = message.take(ProblemReport.MAX_MESSAGE),
        app = AppDetails(AppInfo.NAME, AppInfo.VERSION),
        platform = context.platform,
        screen = context.screen,
        connection = connection,
        bridge = bridge,
        profile = ProfileDetails(
            name = profile.name,
            factory = factory,
            midiChannel = profile.midiChannel,
            ccMap = if (factory) null else profile.ccMap.assignments.entries.associate { (control, cc) -> controlName(control) to cc },
            padNotes = if (factory) null else profile.ccMap.padNotes,
        ),
        settings = mapOf(
            "autoPushOnConnect" to settings.autoPushOnConnect.toString(),
            "linkMixerScenes" to settings.linkMixerScenes.toString(),
            "powerOffBridgeOnExit" to settings.powerOffBridgeOnExit.toString(),
            "bridgeMode" to settings.bridgeMode.toString(),
            "showMidiLog" to settings.showMidiLog.toString(),
            "bridgesEntered" to settings.bridges.size.toString(),
        ),
        install = install,
        diagnostics = context.diagnostics.mapValues { (_, v) -> maskAddresses(v) },
    )
}

/** `events.txt`: the app's recent events, then the MIDI overlay's last messages each way. */
fun eventsText(events: List<AppEvent>, midi: List<MidiLogEntry>, profile: Profile): String = buildString {
    appendLine("App events (oldest first)")
    if (events.isEmpty()) appendLine("  (none)")
    events.forEach { appendLine("${it.at}  ${maskAddresses(it.text)}") }
    appendLine()
    appendLine("MIDI, last messages each way (seconds since the app started)")
    if (midi.isEmpty()) appendLine("  (none)")
    midi.forEach { e ->
        val seconds = "${e.atMillis / 1000}.${(e.atMillis % 1000).toString().padStart(3, '0')}"
        val label = CcCodec.decode(e.message, profile)?.let { "  ${it.control} ${it.value}" }.orEmpty()
        val bytes = e.message.toBytes().let { if (it.size > 16) it.take(16).toHex() + " …(${it.size} bytes)" else it.toHex() }
        appendLine("$seconds  ${if (e.outgoing) "OUT" else "IN "}  $bytes$label")
    }
}

private fun routeOf(kind: MidiDeviceInfo.Kind) = when (kind) {
    MidiDeviceInfo.Kind.USB -> "USB cable"
    MidiDeviceInfo.Kind.BLUETOOTH -> "Bluetooth"
    MidiDeviceInfo.Kind.NETWORK -> "Wi-Fi bridge"
    MidiDeviceInfo.Kind.SIMULATOR -> "Simulator"
}

private fun controlName(control: Control) = when (control) {
    is Control.Channel -> "CH${control.channel} ${control.param.name}"
    is Control.Global -> control.param.name
}

private val IPV4 = Regex("""\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b""")

/**
 * Replaces IP addresses in [text], except the ones that say something useful without saying where
 * the user lives: the Pi's hotspot (10.42.0.x), this computer (127.x) and the Android emulator's
 * host (10.0.2.2).
 */
fun maskAddresses(text: String): String = IPV4.replace(text) { m ->
    val host = m.value
    if (host.startsWith("10.42.0.") || host.startsWith("127.") || host == "10.0.2.2") host
    else if (isPrivate(m.groupValues.drop(1).map { it.toIntOrNull() ?: 0 })) "<private address>"
    else "<address>"
}

private fun isPrivate(o: List<Int>) = o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)

private fun describeAddress(host: String): String = when {
    host.startsWith("10.42.0.") -> "the Pi's hotspot ($host)"
    host.startsWith("127.") || host == "localhost" -> "this computer"
    host == "10.0.2.2" -> "the emulator's host"
    IPV4.matches(host) -> if (isPrivate(host.split('.').map { it.toIntOrNull() ?: 0 })) "a private network" else "a public address"
    else -> "a host name"
}

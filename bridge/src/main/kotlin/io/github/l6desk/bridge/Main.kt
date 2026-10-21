package io.github.l6desk.bridge

import io.github.l6desk.AppInfo
import io.github.l6desk.midi.CcCodec
import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.JavaSoundMidiTransport
import io.github.l6desk.midi.MidiTransport
import io.github.l6desk.midi.MultiTransport
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.midi.toHex
import io.github.l6desk.model.Profile
import io.github.l6desk.net.BridgeHealth
import io.github.l6desk.net.BridgeInfo
import io.github.l6desk.net.BridgeServer
import io.github.l6desk.padcard.MountedPadCard
import io.github.l6desk.recordings.MountedRecordings
import io.github.l6desk.player.FfmpegConverter
import io.github.l6desk.player.JavaSoundPcmOutput
import io.github.l6desk.player.PlayerState
import io.github.l6desk.player.WavPlayer
import io.github.l6desk.sync.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/** Logs go to stdout; under systemd they land in the journal (`journalctl -u l6desk-bridge`). */
internal fun log(text: String) = println("${LocalTime.now().format(TIME)}  $text")

/**
 * The headless Wi-Fi bridge: connects to the L6max Mixer Control port on USB (and reconnects when
 * it's replugged), serves one tablet at a time over TCP, and shows its state on the board's LED.
 */
fun main(args: Array<String>) {
    val config = try {
        BridgeConfig.load(args) { log("WARNING: $it") }
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println(BridgeConfig.USAGE)
        exitProcess(2)
    }
    if (config == null) {
        println(BridgeConfig.USAGE)
        return
    }
    runBlocking { runBridge(config) }
}

suspend fun runBridge(config: BridgeConfig) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    log("${AppInfo.NAME} headless bridge ${AppInfo.VERSION} starting: \"${config.name}\", port ${config.port}")

    val mixer: MidiTransport = if (config.simulator) {
        log("Using the built-in L6max simulator (no USB)")
        SimulatorTransport()
    } else {
        MultiTransport(listOf(JavaSoundMidiTransport(scope)), scope)
    }
    // Same rules as the app: the L6max Mixer Control port whenever one is plugged in.
    ConnectionManager(mixer, scope)

    val player = openPlayer(config, scope)
    val powerOff = PowerOff().takeIf { config.powerOff && it.available }
    log(
        when {
            powerOff != null -> "The app may power the Pi off (power_off=true)"
            config.powerOff -> "Powering off from the app isn't available here (not run by the l6desk-bridge service)"
            else -> "Powering off from the app is turned off (power_off=false)"
        },
    )
    val requestPowerOff: (() -> Unit)? = powerOff?.let { p ->
        {
            log("The app asked to power the Pi off: shutting down")
            if (!p.request()) log("WARNING: couldn't ask for the power-off (${PowerOff.DEFAULT_REQUEST})")
        }
    }
    // The Pi's health for the app (protocol v5): under-voltage, temperature, space for songs.
    val health = BridgeHealth(BridgeInfo.KIND_PI, config.music.takeIf { it.isNotBlank() }?.let(::File))
    health.start(scope) { log("WARNING: under-voltage: the Pi's power supply is too weak (use a 5 V 2.5 A supply and a short, thick cable)") }
    scope.launch { health.warnings.collect { if (it.isNotEmpty()) log("Health: ${it.joinToString("; ")}") } }
    log(if (config.pin.isEmpty()) "No PIN: any app on the network can connect (pin= in the config)" else "Apps must give the PIN to connect")
    health.describe().let { log("Running on ${it.board ?: it.os}${it.temperatureC?.let { t -> ", $t °C" } ?: ""}") }
    val padCard = openPadCard(config, player, scope)
    // RECORDINGS through the Pi: the same card, set up the same way.
    val recordings = padCard?.let { MountedRecordings(File(MountedPadCard.DEFAULT_MOUNT), log = ::log).also { r -> r.start(scope) } }
    val bridge = BridgeServer(
        mixer, scope, config.name, player = player, powerOff = requestPowerOff,
        info = health::describe, infoUpdates = health.warnings, pin = config.pin.ifEmpty { null }, padCard = padCard, recordings = recordings, log = ::log,
    ) { message ->
        if (config.logMidi) {
            val decoded = CcCodec.decode(message, Profile.FACTORY)?.let { "  ${it.control} = ${it.value}" } ?: ""
            log("tablet → mixer  ${message.toBytes().toHex()}$decoded")
        }
    }
    val led = StatusLed.open(config.led, scope, ::log)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            log("Stopping")
            player?.shutdown()
            bridge.stop()
            mixer.close()
            led?.off()
            scope.cancel()
        },
    )

    bridge.start(config.port)

    // Report every change of the mixer or the tablet connection, and mirror it on the LED.
    var lastLine = ""
    combine(mixer.state, bridge.status) { m, b -> m to b }.collect { (m, b) ->
        val line = buildString {
            append(
                when (m) {
                    is ConnectionState.Connected -> "Mixer: ${m.device.name}"
                    is ConnectionState.Connecting -> "Mixer: connecting to ${m.device.name}"
                    is ConnectionState.Error -> "Mixer: ${m.reason}"
                    ConnectionState.Disconnected -> "Mixer: waiting for the L6max on USB"
                },
            )
            append(" | ")
            append(
                when {
                    b.error != null -> "Bridge: ${b.error}"
                    !b.running -> "Bridge: starting"
                    b.client != null -> "Tablet: ${b.client}"
                    else -> "Tablet: none (listening on ${b.addresses.joinToString { "$it:${b.port}" }.ifEmpty { "port ${b.port}" }})"
                },
            )
        }
        if (line != lastLine) {
            log(line)
            lastLine = line
        }
        led?.show(LedPattern.of(m is ConnectionState.Connected, b.client != null, b.error != null))
    }
}

/**
 * PAD SOUNDS through the Pi: the L6max's card, mounted by the Pi (pi-setup.sh's udev rule) while
 * the mixer is in file transfer mode, with the music library's songs to choose from. Only under the
 * l6desk-bridge service (it ejects the card through its runtime directory) with the rule installed.
 */
private fun openPadCard(config: BridgeConfig, player: WavPlayer?, scope: CoroutineScope): MountedPadCard? {
    if (config.simulator) return null
    val request = File(MountedPadCard.DEFAULT_EJECT_REQUEST)
    val dir = request.parentFile
    if (dir == null || !dir.isDirectory || !dir.canWrite() || !File(CARD_RULE).isFile) {
        log("PAD SOUNDS through the Pi: not set up (run pi-setup.sh install)")
        return null
    }
    val music = player?.let { File(config.music) }
    val card = MountedPadCard(
        File(MountedPadCard.DEFAULT_MOUNT), music, { player?.tracks?.value.orEmpty() }, music?.let { File(it, MountedPadCard.BACKUPS) },
        request, FfmpegConverter.find(), log = ::log,
    )
    card.start(scope)
    log(if (player != null) "PAD SOUNDS: songs in the music library can be put on the sound pads (L6max in file transfer mode)" else "PAD SOUNDS: the music player is off, so pads can only be cleared")
    return card
}

private const val CARD_RULE = "/etc/udev/rules.d/90-l6desk-card.rules"

/** The music player on [BridgeConfig.music], or null if it's turned off or the folder is missing. */
private fun openPlayer(config: BridgeConfig, scope: CoroutineScope): WavPlayer? {
    if (config.music.isBlank()) return null
    val dir = File(config.music)
    if (!dir.isDirectory) {
        log("Music player off: no folder ${config.music}")
        return null
    }
    val converter = FfmpegConverter.find()
    val player = WavPlayer(dir, scope, JavaSoundPcmOutput::open, JavaSoundPcmOutput::isAvailable, converter = converter, log = { log("Music: $it") })
    log("Music player: ${player.tracks.value.size} song(s) in ${player.state.value.playlists.size} playlist(s) in ${config.music}")
    if (converter == null) log("WARNING: ffmpeg isn't installed, so only WAV files can be added (pi-setup.sh install adds it)")
    var last: PlayerState? = null
    scope.launch {
        player.state.collect { s ->
            val was = last
            last = s
            if (was?.playing != s.playing) log(s.playing?.let { "Playing \"$it\" on ${s.output.channel} (${s.output.usb})" } ?: "Player stopped")
            if (s.error != null && was?.error != s.error) log("Player: ${s.error}")
        }
    }
    return player
}

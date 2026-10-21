package io.github.l6desk

import io.github.l6desk.midi.CcCodec
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.midi.toHex
import io.github.l6desk.model.Profile
import io.github.l6desk.net.BridgeProtocol
import io.github.l6desk.net.BridgeServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A Wi-Fi bridge with the simulator behind it, for testing the tablet (or the emulator, which
 * reaches this computer at 10.0.2.2) without the L6max. Prints connections and every message.
 *   ./gradlew :desktopApp:runBridgeSim [--args="PORT [CARD]"]   or   scripts/bridge-sim.sh [PORT [CARD]]
 * CARD: an L6max card's folder (e.g. the real one, /media/<user>/ZOOM_SD, read only) whose
 * recordings it offers, as the Pi does (protocol v8).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val port = args.firstOrNull()?.toIntOrNull() ?: BridgeProtocol.DEFAULT_PORT
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    fun log(text: String) = println("${LocalTime.now().format(time)}  $text")

    val mixer = SimulatorTransport()
    mixer.open(SimulatorTransport.DEVICE)
    val recordings = args.getOrNull(1)?.let { dir ->
        io.github.l6desk.recordings.MountedRecordings(java.io.File(dir), log = ::log).also {
            it.start(scope)
            log("Offering the recordings on the card at $dir")
        }
    }
    val bridge = BridgeServer(mixer, scope, "bridge-sim", recordings = recordings) { message ->
        val decoded = CcCodec.decode(message, Profile.FACTORY)?.let { "${it.control} = ${it.value}" } ?: ""
        log("← tablet  ${message.toBytes().toHex()}  $decoded")
    }
    bridge.start(port)
    log("Bridge simulator: mixer = ${SimulatorTransport.DEVICE.name}. Ctrl+C to stop.")
    bridge.status.collect { s ->
        when {
            s.error != null -> log("ERROR: ${s.error}")
            s.client != null -> log("Client connected: ${s.client}")
            s.running -> log("Listening on port ${s.port}. Addresses: ${s.addresses.joinToString()} (emulator: 10.0.2.2:${s.port})")
        }
    }
}

package io.github.l6desk

import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.l6desk.midi.JavaSoundMidiTransport
import io.github.l6desk.midi.JavaSoundPorts
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.net.BridgeDiscovery
import io.github.l6desk.net.AnnouncedBridge
import io.github.l6desk.net.BridgeHealth
import io.github.l6desk.net.BridgeInfo
import io.github.l6desk.net.BridgeServer
import io.github.l6desk.net.JavaDatagramChannel
import io.github.l6desk.net.JavaSocketConnector
import io.github.l6desk.net.NetworkMidiTransport
import io.github.l6desk.persistence.AppStorage
import io.github.l6desk.persistence.FileTextStore
import io.github.l6desk.persistence.desktopSettingsDir
import io.github.l6desk.report.FileReportKey
import io.github.l6desk.player.LocalPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun main() {
    // Lives as long as the app: polls for the L6max being plugged in and out.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val usb = JavaSoundMidiTransport(appScope)
    // A bridge client too, so one desktop can control a mixer through a Pi or another desktop in
    // Bridge mode; bridges announcing themselves on the network are listed automatically.
    val network = NetworkMidiTransport(
        appScope, JavaSocketConnector, BridgeServer.defaultBridgeName(),
        discovery = BridgeDiscovery(appScope, JavaDatagramChannel::open),
    )
    val transports = listOf(usb, network, SimulatorTransport())
    val settingsDir = desktopSettingsDir()
    val storage = AppStorage(FileTextStore(settingsDir))
    val reportKey = FileReportKey(java.io.File(settingsDir, FileReportKey.FILE_NAME))
    val icon = object {}.javaClass.getResourceAsStream("/l6desk-icon.png")
        ?.use { BitmapPainter(it.readAllBytes().decodeToImageBitmap()) }
    // The music player on this computer, for when the L6max is on USB here: made on first use.
    var localPlayer: LocalPlayer? = null
    val makeLocalPlayer = {
        LocalPlayer(
            LocalPlayer.defaultDir(), appScope, io.github.l6desk.player.JavaSoundPcmOutput::open, io.github.l6desk.player.JavaSoundPcmOutput::isAvailable,
            io.github.l6desk.player.FfmpegConverter.find(), io.github.l6desk.player.JavaSoundPcmOutput::pairs,
        ).also { localPlayer = it }
    }
    // The close button asks the app, which may first ask whether to shut down the Pi bridge too.
    val exits = ExitRequests()
    application {
        Window(
            onCloseRequest = exits::request,
            title = "${AppInfo.NAME} ${AppInfo.VERSION}",
            icon = icon,
            state = rememberWindowState(width = 1340.dp, height = 800.dp),
        ) {
            // Below this the eight strips no longer fit side by side.
            window.minimumSize = java.awt.Dimension(900, 560)
            val picker = androidx.compose.runtime.remember { AudioFilePicker { window } }
            val reportFiles = androidx.compose.runtime.remember { ReportFiles { window } }
            val padCard = androidx.compose.runtime.remember { PadCardDesktop(appScope) { window } }
            val recordings = androidx.compose.runtime.remember { RecordingsDesktop(appScope, padCard::eject) }
            App(
                transports, storage,
                // Bridge mode describes itself as a desktop (protocol v5), so a report shows which bridge it was.
                // ... and announces itself on the network, so tablets list it like the Pi.
                bridgeFactory = { mixer, scope ->
                    val name = BridgeServer.defaultBridgeName()
                    AnnouncedBridge(BridgeServer(mixer, scope, name, info = BridgeHealth(BridgeInfo.KIND_DESKTOP)::describe), name, scope)
                },
                pickAudioFiles = picker::pick,
                exitRequests = exits,
                reports = ReportSupport(reportKey, reportFiles::save, openUrl = reportFiles::openUrl),
                // Not on Windows yet: Zoom's driver fixes the L6max at 4 channels, and Java Sound
                // (DirectSound) can only open 1 or 2 there. The Pi bridge's player works as usual.
                localPlayer = makeLocalPlayer.takeUnless { System.getProperty("os.name").orEmpty().startsWith("Windows") },
                padCard = padCard,
                recordings = recordings,
                recordingSaver = androidx.compose.runtime.remember { DesktopRecordingSaver() },
                diagnostics = {
                    mapOf(
                        "midiDevices" to JavaSoundPorts.describeAll().joinToString("\n"),
                        "audioDevices" to io.github.l6desk.player.JavaSoundPcmOutput.describeAll().joinToString("\n") +
                            (io.github.l6desk.player.JavaSoundPcmOutput.lastStereoFormat?.let { "\nStereo device opened at $it" } ?: "") +
                            io.github.l6desk.player.JavaSoundPcmOutput.lastStereoFailures.joinToString("") { "\nRefused $it" },
                        "ffmpeg" to (io.github.l6desk.player.FfmpegConverter.find()?.let { "found" } ?: "not found"),
                    )
                },
                onExit = {
                    localPlayer?.shutdown()
                    usb.close()
                    exitApplication()
                },
            )
        }
    }
}

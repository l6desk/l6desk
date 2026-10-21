package io.github.l6desk

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.net.BridgeDiscovery
import io.github.l6desk.net.JavaDatagramChannel
import io.github.l6desk.net.JavaSocketConnector
import io.github.l6desk.net.NetworkMidiTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import io.github.l6desk.persistence.AppStorage
import io.github.l6desk.persistence.FileTextStore
import io.github.l6desk.report.KeystoreReportKey

class MainActivity : ComponentActivity() {
    // USB MIDI (the L6max plugged into the tablet), the Wi-Fi bridge client, and the simulator.
    // Bluetooth MIDI is deferred until there's an adapter to test with.
    // Off the UI thread (as on the desktop): reading the bridge, its watchdog and downloads must
    // not wait while the screen is busy drawing (a download crawled behind a pulsing button).
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transports by lazy {
        listOf(
            AndroidMidiTransport(applicationContext),
            NetworkMidiTransport(
                appScope, JavaSocketConnector, android.os.Build.MODEL ?: "Android tablet",
                discovery = BridgeDiscovery(appScope, JavaDatagramChannel::open),
            ),
            SimulatorTransport(),
        )
    }
    private val storage by lazy { AppStorage(FileTextStore(filesDir)) }

    // The music player on the tablet, when the L6max is plugged in by USB (no Pi): songs in the
    // app's own Music folder (Android/data/io.github.l6desk/files/Music/L6Desk, reachable from a
    // computer over USB), played into the L6max's USB audio. Made on first use only.
    private var localPlayer: io.github.l6desk.player.LocalPlayer? = null
    private val makeLocalPlayer = {
        io.github.l6desk.player.LocalPlayer(
            java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: filesDir, "L6Desk"), appScope,
            { AndroidPcmOutput.open(applicationContext) }, { AndroidPcmOutput.isAvailable(applicationContext) },
            MediaCodecConverter, { AndroidPcmOutput.pairs(applicationContext) }, where = "this tablet",
            log = { android.util.Log.i("L6Desk", it) },
        ).also { localPlayer = it }
    }

    // Registered with the activity as it's created (Android requires that before it starts).
    private val audioPicker = AudioFilePicker(this)
    private val reportFiles = ReportFiles(this)
    private val reports by lazy { ReportSupport(KeystoreReportKey(), reportFiles::save, share = reportFiles::share) }

    // Back at the desk closes the app, which may first ask whether to shut down the Pi bridge too.
    // (Swiping the app away in the recent-apps list closes it without asking, and leaves the Pi on.)
    private val exits = ExitRequests()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark desk: light status and navigation bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Full screen: every pixel goes to fader travel. A swipe from the edge shows the bars briefly.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onBackPressedDispatcher.addCallback(this) { exits.request() }
        setContent {
            App(
                transports, storage,
                keepScreenOn = { on ->
                    // A mixing desk must not go to sleep mid-show; only while connected, to save battery.
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                },
                pickAudioFiles = audioPicker::pick,
                exitRequests = exits,
                reports = reports,
                recordingSaver = AndroidRecordingSaver(applicationContext),
                localPlayer = makeLocalPlayer,
                onExit = ::finish,
            )
        }
    }

    override fun onDestroy() {
        localPlayer?.shutdown()
        appScope.cancel()
        super.onDestroy()
    }
}

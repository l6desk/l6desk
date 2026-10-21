package io.github.l6desk.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.l6desk.App
import io.github.l6desk.ExitRequests
import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.net.BridgeServer
import io.github.l6desk.net.JavaSocketConnector
import io.github.l6desk.net.NetworkMidiTransport
import io.github.l6desk.persistence.AppSettings
import io.github.l6desk.persistence.AppStorage
import io.github.l6desk.persistence.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Closing the app while on a Pi bridge: asks, then shuts the Pi down (or not), then closes. */
@OptIn(ExperimentalTestApi::class)
class ExitUiTest {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val poweredOff = AtomicInteger()
    private val exits = AtomicInteger()

    @AfterTest
    fun tearDown() = io.cancel()

    /** Storage set up to connect to a bridge (that can power off if [canPowerOff]) on localhost. */
    private fun piBridge(canPowerOff: Boolean = true, settings: AppSettings = AppSettings()): Pair<NetworkMidiTransport, AppStorage> {
        val mixer = SimulatorTransport().also { runBlocking { it.open(SimulatorTransport.DEVICE) } }
        val bridge = BridgeServer(mixer, io, "pi", powerOff = { poweredOff.incrementAndGet(); Unit }.takeIf { canPowerOff }).also { it.start(0) }
        runBlocking { while (!bridge.status.value.running) kotlinx.coroutines.delay(20) }
        val address = "127.0.0.1:${bridge.status.value.port}"
        val store = MemoryStore()
        AppStorage(store).saveSettings(settings.copy(bridges = listOf(address), lastDeviceId = "bridge:$address"))
        return NetworkMidiTransport(io, JavaSocketConnector, "UI test") to AppStorage(store)
    }

    @Test
    fun onAPiBridgeClosingAsksAndCanShutItDown() = runDesktopComposeUiTest(1340, 800) {
        val (net, storage) = piBridge()
        val requests = ExitRequests()
        setContent { App(listOf(net, SimulatorTransport()), storage, exitRequests = requests, onExit = { exits.incrementAndGet() }) }
        waitUntil(timeoutMillis = 5_000) { net.state.value is ConnectionState.Connected && net.canPowerOff.value }

        requests.request()
        onNodeWithTag("exit-dialog").assertExists()
        onNodeWithTag("exit-cancel").performClick()
        waitForIdle()
        onNodeWithTag("exit-dialog").assertDoesNotExist()
        assertEquals(0, exits.get(), "CANCEL keeps the app open")

        requests.request()
        onNodeWithTag("exit-close-only").performClick()
        waitForIdle()
        assertEquals(1, exits.get())
        assertEquals(0, poweredOff.get(), "CLOSE, LEAVE PI ON")

        requests.request()
        onNodeWithTag("exit-power-off").performClick()
        waitUntil(timeoutMillis = 5_000) { exits.get() == 2 }
        assertEquals(1, poweredOff.get(), "the Pi was asked, and acknowledged before the app closed")
    }

    @Test
    fun withTheOptionOffItClosesWithoutAsking() = runDesktopComposeUiTest(1340, 800) {
        val (net, storage) = piBridge(settings = AppSettings(powerOffBridgeOnExit = false))
        val requests = ExitRequests()
        setContent { App(listOf(net, SimulatorTransport()), storage, exitRequests = requests, onExit = { exits.incrementAndGet() }) }
        waitUntil(timeoutMillis = 5_000) { net.canPowerOff.value }
        requests.request()
        waitUntil(timeoutMillis = 2_000) { exits.get() == 1 }
        onNodeWithTag("exit-dialog").assertDoesNotExist()
        assertEquals(0, poweredOff.get())
    }

    @Test
    fun aBridgeThatCantPowerOffClosesWithoutAsking() = runDesktopComposeUiTest(1340, 800) {
        // E.g. a desktop in Bridge mode, or a Pi with power_off=false.
        val (net, storage) = piBridge(canPowerOff = false)
        val requests = ExitRequests()
        setContent { App(listOf(net, SimulatorTransport()), storage, exitRequests = requests, onExit = { exits.incrementAndGet() }) }
        waitUntil(timeoutMillis = 5_000) { net.state.value is ConnectionState.Connected }
        requests.request()
        waitUntil(timeoutMillis = 2_000) { exits.get() == 1 }
        onNodeWithTag("exit-dialog").assertDoesNotExist()
    }
}

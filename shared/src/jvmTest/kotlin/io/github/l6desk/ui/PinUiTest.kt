package io.github.l6desk.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.l6desk.App
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A bridge with a PIN: the app asks once, says when it's wrong, and remembers the right one. */
@OptIn(ExperimentalTestApi::class)
class PinUiTest {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = io.cancel()

    @Test
    fun theAppAsksForThePinOnceAndRemembersIt() {
        val mixer = SimulatorTransport().also { runBlocking { it.open(SimulatorTransport.DEVICE) } }
        val bridge = BridgeServer(mixer, io, "pi", pin = "2468").also { it.start(0) }
        runBlocking { while (!bridge.status.value.running) kotlinx.coroutines.delay(20) }
        val address = "127.0.0.1:${bridge.status.value.port}"
        val store = MemoryStore()
        AppStorage(store).saveSettings(AppSettings(bridges = listOf(address), lastDeviceId = "bridge:$address"))

        runDesktopComposeUiTest(1340, 800) {
            val net = NetworkMidiTransport(io, JavaSocketConnector, "UI test")
            setContent { App(listOf(net, SimulatorTransport()), AppStorage(store)) }
            waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("pin-dialog").assertExists() }.isSuccess }
            onNodeWithTag("pin-field").performTextInput("1111")
            onNodeWithTag("pin-ok").performClick()
            waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("pin-wrong").assertExists() }.isSuccess }
            onNodeWithTag("pin-field").performTextReplacement("2468")
            onNodeWithTag("pin-ok").performClick()
            waitUntil(timeoutMillis = 5_000) { net.state.value is ConnectionState.Connected }
            onNodeWithTag("pin-dialog").assertDoesNotExist()
            assertEquals(mapOf("bridge:$address" to "2468"), AppStorage(store).loadSettings().bridgePins)
            net.close()
        }

        // Next time: straight in, no question.
        runDesktopComposeUiTest(1340, 800) {
            val net = NetworkMidiTransport(io, JavaSocketConnector, "UI test")
            setContent { App(listOf(net, SimulatorTransport()), AppStorage(store)) }
            waitUntil(timeoutMillis = 5_000) { net.state.value is ConnectionState.Connected }
            onNodeWithTag("pin-dialog").assertDoesNotExist()
            net.close()
        }
    }

    @Test
    fun cancelStopsConnecting() = runDesktopComposeUiTest(1340, 800) {
        val mixer = SimulatorTransport().also { runBlocking { it.open(SimulatorTransport.DEVICE) } }
        val bridge = BridgeServer(mixer, io, "pi", pin = "2468").also { it.start(0) }
        waitUntil(timeoutMillis = 5_000) { bridge.status.value.running }
        val address = "127.0.0.1:${bridge.status.value.port}"
        val store = MemoryStore()
        AppStorage(store).saveSettings(AppSettings(bridges = listOf(address), lastDeviceId = "bridge:$address"))
        val net = NetworkMidiTransport(io, JavaSocketConnector, "UI test")
        setContent { App(listOf(net, SimulatorTransport()), AppStorage(store)) }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("pin-dialog").assertExists() }.isSuccess }
        onNodeWithTag("pin-cancel").performClick()
        waitForIdle()
        onNodeWithTag("pin-dialog").assertDoesNotExist()
        assertEquals(ConnectionState.Disconnected, net.state.value)
    }
}

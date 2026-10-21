package io.github.l6desk.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.l6desk.App
import io.github.l6desk.midi.ConnectionState
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.model.ChannelParam
import io.github.l6desk.model.Control
import io.github.l6desk.net.BridgeHost
import io.github.l6desk.net.BridgeServer
import io.github.l6desk.net.JavaSocketConnector
import io.github.l6desk.net.NetworkMidiTransport
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
import kotlin.test.assertTrue

/** Route C in the app: add a bridge, connect through it, and run Bridge mode. Real TCP on localhost. */
@OptIn(ExperimentalTestApi::class)
class BridgeUiTest {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = io.cancel()

    @Test
    fun addABridgeAndMixThroughIt() = runDesktopComposeUiTest(1340, 800) {
        // The "far end": a bridge with a simulated L6max behind it.
        val farMixer = SimulatorTransport().also { runBlocking { it.open(SimulatorTransport.DEVICE) } }
        val bridge = BridgeServer(farMixer, io, "far-bridge").also { it.start(0) }
        waitUntil(timeoutMillis = 5_000) { bridge.status.value.running }
        val port = bridge.status.value.port

        val net = NetworkMidiTransport(io, JavaSocketConnector, "UI test")
        val store = MemoryStore()
        setContent { App(listOf(net, SimulatorTransport()), AppStorage(store)) }

        onNodeWithTag("connection-button").performClick()
        onNodeWithTag("bridge-address").performTextInput("127.0.0.1:$port")
        onNodeWithTag("bridge-add").performClick()
        waitForIdle()
        assertEquals(listOf("127.0.0.1:$port"), AppStorage(store).loadSettings().bridges, "the bridge is remembered")

        onNodeWithTag("device-bridge:127.0.0.1:$port").performClick()
        waitUntil(timeoutMillis = 5_000) { net.state.value is ConnectionState.Connected }
        assertTrue("far-bridge" in (net.state.value as ConnectionState.Connected).device.name)

        onNodeWithTag("ch8-mute").performClick()
        waitUntil(timeoutMillis = 5_000) { farMixer.valueOf(Control.Channel(8, ChannelParam.MUTE)) == 127 }
    }

    @Test
    fun bridgeModeStartsTheServerAndIsRemembered() = runDesktopComposeUiTest(1340, 800) {
        val store = MemoryStore()
        var server: BridgeServer? = null
        // On any free port, so a real bridge on 5704 (e.g. the app itself, running) doesn't matter.
        val anyPort = { real: BridgeServer -> object : BridgeHost by real { override fun start(port: Int) = real.start(0) } }
        setContent {
            App(listOf(SimulatorTransport()), AppStorage(store), bridgeFactory = { mixer, _ -> anyPort(BridgeServer(mixer, io).also { server = it }) })
        }
        onNodeWithTag("setup-button").performClick()
        onNodeWithTag("option-bridge").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { server?.status?.value?.running == true }
        assertTrue(AppStorage(store).loadSettings().bridgeMode)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Waiting for a tablet", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("option-bridge").performClick()
        waitUntil(timeoutMillis = 5_000) { server?.status?.value?.running == false }
    }
}

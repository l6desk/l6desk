package io.github.l6desk.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.l6desk.App
import io.github.l6desk.midi.SimulatorTransport
import io.github.l6desk.net.BridgeInfo
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test

/** The bridge's warnings (protocol v5) show on the desk: under-voltage red now, amber after; DISMISS until it changes. */
@OptIn(ExperimentalTestApi::class)
class BridgeWarningsUiTest {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = io.cancel()

    @Test
    fun warningsShowOneAtATimeUntilDismissedAndComeBackWhenTheyChange() = runDesktopComposeUiTest(1340, 800) {
        val info = MutableStateFlow(BridgeInfo(BridgeInfo.KIND_PI, "0.14.0", underVoltageNow = false, underVoltageCount = 0))
        val mixer = SimulatorTransport().also { runBlocking { it.open(SimulatorTransport.DEVICE) } }
        val bridge = BridgeServer(mixer, io, "pi", info = { info.value }, infoUpdates = info).also { it.start(0) }
        runBlocking { while (!bridge.status.value.running) kotlinx.coroutines.delay(20) }
        val address = "127.0.0.1:${bridge.status.value.port}"
        val store = MemoryStore()
        AppStorage(store).saveSettings(AppSettings(bridges = listOf(address), lastDeviceId = "bridge:$address"))
        val net = NetworkMidiTransport(io, JavaSocketConnector, "UI test")
        setContent { App(listOf(net, SimulatorTransport()), AppStorage(store)) }
        waitUntil(timeoutMillis = 5_000) { net.bridgeInfo.value != null }
        waitForIdle()
        onNodeWithTag("bridge-warnings").assertDoesNotExist()

        info.value = info.value.copy(underVoltageNow = true, underVoltageCount = 1)
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("bridge-warnings").assertExists() }.isSuccess }
        onNodeWithTag("bridge-warning-text-under-voltage-1", useUnmergedTree = true).assertTextContains("too weak right now", substring = true)

        info.value = info.value.copy(underVoltageNow = false)
        waitUntil(timeoutMillis = 5_000) { net.bridgeInfo.value?.underVoltageNow == false }
        waitForIdle()
        onNodeWithTag("bridge-warning-text-under-voltage-1", useUnmergedTree = true).assertTextContains("dipped too low once", substring = true)

        onNodeWithTag("bridge-warning-dismiss-under-voltage-1").performClick()
        waitForIdle()
        onNodeWithTag("bridge-warnings").assertDoesNotExist()

        info.value = info.value.copy(underVoltageNow = true, underVoltageCount = 2)
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("bridge-warnings").assertExists() }.isSuccess }

        // Heat comes in behind the dip, one row at a time ("1 of 2"); DISMISS moves on to it.
        info.value = info.value.copy(underVoltageNow = false, temperatureC = 83.0)
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("bridge-warning-count", useUnmergedTree = true).assertTextContains("1 of 2") }.isSuccess }
        onNodeWithTag("bridge-warning-dismiss-under-voltage-2").performClick()
        // Heat: dismissed, it stays away while hot; once it cools and heats up again, it's back.
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("bridge-warning-text-hot", useUnmergedTree = true).assertExists() }.isSuccess }
        onNodeWithTag("bridge-warning-dismiss-hot").performClick()
        waitForIdle()
        onNodeWithTag("bridge-warning-text-hot", useUnmergedTree = true).assertDoesNotExist()
        info.value = info.value.copy(temperatureC = 84.0)
        Thread.sleep(300)
        waitForIdle()
        onNodeWithTag("bridge-warning-text-hot", useUnmergedTree = true).assertDoesNotExist()
        info.value = info.value.copy(temperatureC = 60.0)
        waitUntil(timeoutMillis = 5_000) { net.bridgeInfo.value?.temperatureC == 60.0 }
        waitForIdle()
        info.value = info.value.copy(temperatureC = 81.0)
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("bridge-warning-text-hot", useUnmergedTree = true).assertExists() }.isSuccess }
    }
}

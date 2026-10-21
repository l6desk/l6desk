package io.github.l6desk.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Finds the real bridges on this network (e.g. the Pi). Opt-in, since it needs one running:
 *   L6MAX_NETWORK=1 ./gradlew :core:jvmTest --tests '*RealNetworkDiscoveryTest*' -i
 */
class RealNetworkDiscoveryTest {
    @Test
    fun findsABridgeOnThisNetwork() {
        if (System.getenv("L6MAX_NETWORK") != "1") return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val discovery = BridgeDiscovery(scope, JavaDatagramChannel::open)
            discovery.start()
            val found = runBlocking { withTimeoutOrNull(5.seconds) { while (discovery.found.value.isEmpty()) delay(50); discovery.found.value } }
            println("Found bridges: $found")
            assertTrue(!found.isNullOrEmpty(), "no bridge answered within 5 s")
        } finally {
            scope.cancel()
        }
    }
}

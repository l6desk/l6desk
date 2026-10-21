package io.github.l6desk.midi

import io.github.l6desk.model.L6maxLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Talks to a real L6max over USB, read-only. Skipped unless L6MAX_HARDWARE=1, and only while no
 * other app holds the ports:  L6MAX_HARDWARE=1 ./gradlew :shared:jvmTest --tests '*RealL6maxTest*'
 */
class RealL6maxTest {
    private val enabled = System.getenv("L6MAX_HARDWARE") == "1"

    @Test
    fun findsTheMixerControlPortAndReadsTheFactoryMap() {
        if (!enabled) return
        val scope = CoroutineScope(SupervisorJob())
        try {
            val t = JavaSoundMidiTransport(scope)
            t.refresh()
            val mixer = t.devices.value.single { it.isL6maxMixerControl }
            println("Mixer Control port: ${mixer.name}")
            val map = runBlocking { t.readCcMap(mixer) }
            assertEquals(L6maxLayout.FACTORY_CC_MAP, map)
            println("Read ${map.assignments.size} CC assignments over the Editor port; they match the factory map")
        } finally {
            scope.cancel()
        }
    }
}

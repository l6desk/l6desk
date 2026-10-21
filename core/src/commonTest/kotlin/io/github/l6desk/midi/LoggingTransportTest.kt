package io.github.l6desk.midi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LoggingTransportTest {
    @Test
    fun keepsTheLastMessagesInEachDirection() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val inner = FakeTransport()
        val logged = LoggingTransport(inner, scope, keep = 3)
        logged.incoming.launchIn(scope)

        repeat(5) { logged.send(MidiMessage.ControlChange(1, 81, it)) }
        inner.incoming.emit(MidiMessage.ControlChange(1, 93, 127))

        assertEquals(5, inner.sent.size, "everything still passes through")
        val log = logged.log.value
        assertEquals(listOf(2, 3, 4), log.filter { it.outgoing }.map { (it.message as MidiMessage.ControlChange).value })
        assertEquals(1, log.count { !it.outgoing })
        assertEquals(3 to 1, logged.rates())
    }
}

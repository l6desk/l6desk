package io.github.l6desk.midi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CoroutineScope
import kotlin.time.TimeSource

/** One message seen by [LoggingTransport]. [atMillis] counts from when logging started. */
data class MidiLogEntry(val outgoing: Boolean, val message: MidiMessage, val atMillis: Long)

/**
 * Records the last messages in each direction for the debug overlay (CLAUDE.md, Rules),
 * without changing what passes through.
 */
class LoggingTransport(
    private val inner: MidiTransport,
    scope: CoroutineScope,
    private val keep: Int = 20,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : MidiTransport by inner {
    private val start = timeSource.markNow()
    private val _log = MutableStateFlow<List<MidiLogEntry>>(emptyList())

    /** The last [keep] messages sent and the last [keep] received, oldest first. */
    val log: StateFlow<List<MidiLogEntry>> = _log.asStateFlow()

    override val incoming: SharedFlow<MidiMessage> =
        inner.incoming.onEach { record(false, it) }.shareIn(scope, SharingStarted.Eagerly)

    override fun send(message: MidiMessage) {
        record(true, message)
        inner.send(message)
    }

    private fun record(outgoing: Boolean, message: MidiMessage) {
        val entry = MidiLogEntry(outgoing, message, start.elapsedNow().inWholeMilliseconds)
        while (true) {
            val current = _log.value
            val sameDirection = current.filter { it.outgoing == outgoing }
            val trimmed = if (sameDirection.size >= keep) current - sameDirection.first() else current
            if (_log.compareAndSet(current, trimmed + entry)) return
        }
    }

    /** Messages per second in each direction over the last second: (out, in). */
    fun rates(): Pair<Int, Int> {
        val now = start.elapsedNow().inWholeMilliseconds
        val recent = _log.value.filter { now - it.atMillis <= 1_000 }
        return recent.count { it.outgoing } to recent.count { !it.outgoing }
    }
}

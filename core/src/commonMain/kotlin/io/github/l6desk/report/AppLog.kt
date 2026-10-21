package io.github.l6desk.report

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** One thing that happened in the app, at [at] (ISO 8601, UTC). */
data class AppEvent(val at: String, val text: String)

/**
 * The last [keep] app events (connections, errors, uploads…), kept in memory only. Problem reports
 * include them, so a report shows what led up to the problem.
 */
class AppLog(private val keep: Int = 200, private val now: () -> String = ::isoNow) {
    private val _events = MutableStateFlow<List<AppEvent>>(emptyList())
    val events: StateFlow<List<AppEvent>> = _events.asStateFlow()

    fun add(text: String) {
        val event = AppEvent(now(), text)
        _events.update { (it + event).takeLast(keep) }
    }
}

/** The time now, ISO 8601 in UTC ("2026-10-11T10:00:00.123Z"). */
@OptIn(ExperimentalTime::class)
fun isoNow(): String = Clock.System.now().toString()

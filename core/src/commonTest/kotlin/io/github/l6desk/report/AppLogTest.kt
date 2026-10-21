package io.github.l6desk.report

import kotlin.test.Test
import kotlin.test.assertEquals

class AppLogTest {
    @Test
    fun keepsTheLatestEventsOldestFirst() {
        var t = 0
        val log = AppLog(keep = 3) { "t${t++}" }
        listOf("a", "b", "c", "d").forEach(log::add)
        assertEquals(listOf(AppEvent("t1", "b"), AppEvent("t2", "c"), AppEvent("t3", "d")), log.events.value)
    }

    @Test
    fun timesAreIsoUtc() {
        assertEquals(true, Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z""").matches(isoNow()), isoNow())
    }
}

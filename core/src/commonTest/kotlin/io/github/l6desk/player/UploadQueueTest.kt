package io.github.l6desk.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UploadQueueTest {
    private class Source(override val name: String, override val size: Long = 1000, val failRead: Boolean = false) : UploadSource {
        var closed = false
        override suspend fun read(buffer: ByteArray): Int = if (failRead) throw IllegalStateException("gone") else -1
        override fun close() { closed = true }
    }

    /** Uploads that finish when the test says so. */
    private class FakeBridge {
        val started = mutableListOf<Pair<String, String>>()
        val results = mutableMapOf<String, CompletableDeferred<UploadStatus>>()
        val progress = mutableMapOf<String, (UploadStatus) -> Unit>()

        suspend fun upload(source: UploadSource, playlist: String, onStatus: (UploadStatus) -> Unit): UploadStatus {
            started += source.name to playlist
            progress[source.name] = onStatus
            source.read(ByteArray(10))
            return results.getOrPut(source.name) { CompletableDeferred() }.await()
        }

        fun finish(name: String, status: UploadStatus) = results.getOrPut(name) { CompletableDeferred() }.complete(status)
    }

    private fun TestScope.queue(bridge: FakeBridge) = UploadQueue(backgroundScope, bridge::upload)

    private fun UploadQueue.stages() = items.value.map { it.name to it.stage }

    @Test
    fun filesGoOneAtATimeInOrderAndReportProgress() = runTest {
        val bridge = FakeBridge()
        val q = queue(bridge)
        val a = Source("a.mp3")
        q.add(listOf(a, Source("b.mp3")), "Set")
        runCurrent()
        assertEquals(listOf("a.mp3" to "Set"), bridge.started, "one at a time")
        assertEquals(listOf("a.mp3" to UploadItem.Stage.SENDING, "b.mp3" to UploadItem.Stage.WAITING), q.stages())

        bridge.progress.getValue("a.mp3")(UploadStatus(1, UploadStage.RECEIVING, received = 500))
        assertEquals(0.5f, q.items.value[0].fraction)
        bridge.progress.getValue("a.mp3")(UploadStatus(1, UploadStage.CONVERTING, received = 1000))
        assertEquals(UploadItem.Stage.CONVERTING, q.items.value[0].stage)

        bridge.finish("a.mp3", UploadStatus(1, UploadStage.DONE, file = "Set/01 a.wav"))
        runCurrent()
        assertTrue(a.closed)
        assertEquals(listOf("a.mp3" to UploadItem.Stage.DONE, "b.mp3" to UploadItem.Stage.SENDING), q.stages())

        bridge.finish("b.mp3", UploadStatus(2, UploadStage.FAILED, message = "SD card nearly full"))
        runCurrent()
        assertEquals(UploadItem.Stage.FAILED, q.items.value[1].stage)
        assertEquals("SD card nearly full", q.items.value[1].message)

        q.clearFinished()
        assertEquals(emptyList(), q.items.value)
    }

    @Test
    fun cancellingAWaitingFileSkipsItAndCancellingTheCurrentOneStopsIt() = runTest {
        val bridge = FakeBridge()
        val q = queue(bridge)
        val b = Source("b.mp3")
        q.add(listOf(Source("a.mp3"), b, Source("c.mp3")), "Set")
        runCurrent()
        val ids = q.items.value.map { it.id }
        q.cancel(ids[1])
        q.cancel(ids[0])
        runCurrent()
        assertEquals(
            listOf("a.mp3" to UploadItem.Stage.CANCELLED, "b.mp3" to UploadItem.Stage.CANCELLED, "c.mp3" to UploadItem.Stage.SENDING),
            q.stages(),
        )
        assertEquals(listOf("a.mp3", "c.mp3"), bridge.started.map { it.first }, "b was never sent")
        assertTrue(b.closed)
    }

    @Test
    fun aFileThatCantBeReadFailsAndTheNextGoesOn() = runTest {
        val bridge = FakeBridge()
        val q = queue(bridge)
        q.add(listOf(Source("bad.mp3", failRead = true), Source("good.mp3")), "Set")
        runCurrent()
        assertEquals(UploadItem.Stage.FAILED, q.items.value[0].stage)
        assertTrue("Can't read" in q.items.value[0].message.orEmpty())
        assertEquals(UploadItem.Stage.SENDING, q.items.value[1].stage)
    }

    @Test
    fun anUnknownSizeShowsNoPercentage() = runTest {
        val q = UploadQueue(backgroundScope) { _, _, onStatus ->
            onStatus(UploadStatus(1, UploadStage.RECEIVING, received = 500))
            awaitCancellation()
        }
        q.add(listOf(Source("a.m4a", size = -1)), "Set")
        runCurrent()
        assertEquals(500, q.items.value[0].sent)
        assertEquals(null, q.items.value[0].fraction)
        backgroundScope.cancel()
    }
}

package io.github.l6desk.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One file in the app's upload list. */
data class UploadItem(
    val id: Int,
    val name: String,
    val playlist: String,
    /** Bytes, or −1 if unknown. */
    val size: Long,
    val stage: Stage = Stage.WAITING,
    val sent: Long = 0,
    val message: String? = null,
) {
    enum class Stage { WAITING, SENDING, CONVERTING, DONE, FAILED, CANCELLED }

    val finished: Boolean get() = stage == Stage.DONE || stage == Stage.FAILED || stage == Stage.CANCELLED

    /** 0–1 while sending, when the size is known. */
    val fraction: Float? get() = if (size > 0 && stage == Stage.SENDING) (sent.toFloat() / size).coerceIn(0f, 1f) else null
}

/**
 * The app's uploads to the bridge: files picked by the user, sent one at a time by [upload]
 * (NetworkMidiTransport.upload), carrying on while the PLAYER panel is closed.
 */
class UploadQueue(
    private val scope: CoroutineScope,
    private val upload: suspend (UploadSource, String, (UploadStatus) -> Unit) -> UploadStatus,
) {
    private val _items = MutableStateFlow<List<UploadItem>>(emptyList())
    val items: StateFlow<List<UploadItem>> = _items.asStateFlow()

    private val waiting = Channel<Pair<Int, UploadSource>>(Channel.UNLIMITED)
    private var nextId = 1
    private var current: Pair<Int, Job>? = null

    init {
        scope.launch {
            for ((id, source) in waiting) {
                if (_items.value.firstOrNull { it.id == id }?.stage != UploadItem.Stage.WAITING) {
                    source.close()
                    continue
                }
                val job = launch { send(id, source) }
                current = id to job
                job.join()
                current = null
            }
        }
    }

    /** Queues [sources] to be added to the end of [playlist], in order. */
    fun add(sources: List<UploadSource>, playlist: String) {
        for (source in sources) {
            val id = nextId++
            _items.update { it + UploadItem(id, source.name, playlist, source.size) }
            waiting.trySend(id to source)
        }
    }

    fun cancel(id: Int) {
        val running = current
        if (running?.first == id) running.second.cancel()
        set(id) { if (it.finished || it.stage == UploadItem.Stage.CONVERTING) it else it.copy(stage = UploadItem.Stage.CANCELLED) }
    }

    /** Removes the finished uploads from the list. */
    fun clearFinished() = _items.update { list -> list.filterNot { it.finished } }

    private suspend fun send(id: Int, source: UploadSource) {
        val item = _items.value.first { it.id == id }
        set(id) { it.copy(stage = UploadItem.Stage.SENDING) }
        try {
            val result = upload(source, item.playlist) { status ->
                set(id) {
                    when (status.stage) {
                        UploadStage.RECEIVING -> it.copy(sent = status.received)
                        UploadStage.CONVERTING -> it.copy(stage = UploadItem.Stage.CONVERTING, sent = status.received)
                        else -> it
                    }
                }
            }
            set(id) {
                if (result.stage == UploadStage.DONE) it.copy(stage = UploadItem.Stage.DONE, message = null)
                else it.copy(stage = UploadItem.Stage.FAILED, message = result.message ?: "Upload failed")
            }
        } catch (e: CancellationException) {
            set(id) { it.copy(stage = UploadItem.Stage.CANCELLED) }
            throw e
        } catch (e: Exception) {
            set(id) { it.copy(stage = UploadItem.Stage.FAILED, message = "Can't read the file: ${e.message ?: e::class.simpleName}") }
        } finally {
            source.close()
        }
    }

    private fun set(id: Int, change: (UploadItem) -> UploadItem) =
        _items.update { list -> list.map { if (it.id == id) change(it) else it } }
}

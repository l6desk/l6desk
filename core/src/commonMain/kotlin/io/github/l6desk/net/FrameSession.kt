package io.github.l6desk.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** A TCP connection as the bridge code needs it. Implemented with java.net on desktop and Android. */
interface ByteSocket {
    /** Reads into [buffer]; returns the number of bytes read, or -1 at end of stream. */
    suspend fun read(buffer: ByteArray): Int
    suspend fun write(bytes: ByteArray)
    fun close()
    val remoteAddress: String
}

fun interface SocketConnector {
    suspend fun connect(host: String, port: Int, timeout: Duration): ByteSocket
}

class LinkLostException(message: String) : Exception(message)

/**
 * Runs one framed connection: reads frames, writes queued frames from a single writer, sends a
 * ping every [keepalive], and fails with [LinkLostException] after [timeout] without hearing
 * anything. Shared by the bridge client and server.
 */
class FrameSession(
    private val socket: ByteSocket,
    private val keepalive: Duration,
    private val timeout: Duration,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val outbox = Channel<ByteArray>(capacity = 1024)

    // Bulk data (uploads) waits here and goes out only when [outbox] is empty, so MIDI, pings and
    // commands never queue behind a file. Small, so the sender is held back (see [sendBulk]).
    private val bulk = Channel<ByteArray>(capacity = 1)
    private val lastHeard = MutableStateFlow(timeSource.markNow())

    val remoteAddress: String get() = socket.remoteAddress

    /** Queues [frame] for sending; never blocks the caller (safe on the UI thread). */
    fun send(frame: Frame) {
        outbox.trySend(FrameEncoder.encode(frame))
    }

    /**
     * Queues bulk [frame] behind everything sent with [send]; suspends while the bulk queue is full.
     * Throws if the session has ended.
     */
    suspend fun sendBulk(frame: Frame) = bulk.send(FrameEncoder.encode(frame))

    /** Writes [frame] straight away (e.g. a refusal just before closing). */
    suspend fun sendNow(frame: Frame) = socket.write(FrameEncoder.encode(frame))

    /**
     * Runs until the connection fails or the calling coroutine is cancelled, passing every frame
     * except pings to [onFrame]. Always closes the socket before returning or throwing.
     */
    suspend fun run(onFrame: suspend (Frame) -> Unit): Nothing = coroutineScope {
        val failure = CompletableDeferred<Throwable>()
        fun guard(block: suspend () -> Unit) = launch {
            try {
                block()
            } catch (e: Throwable) {
                failure.complete(e)
            }
        }
        val jobs = listOf(
            guard {
                val reader = FrameReader()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = socket.read(buffer)
                    if (n < 0) throw LinkLostException("Connection closed by ${socket.remoteAddress}")
                    lastHeard.value = timeSource.markNow()
                    for (frame in reader.feed(buffer, n)) if (frame != Frame.Ping) onFrame(frame)
                }
            },
            guard {
                while (true) {
                    // select is biased to its first clause: the outbox always goes first.
                    val bytes = select {
                        outbox.onReceive { it }
                        bulk.onReceive { it }
                    }
                    socket.write(bytes)
                }
            },
            guard {
                while (true) {
                    delay(keepalive)
                    send(Frame.Ping)
                }
            },
            guard {
                val step = (keepalive / 4).coerceAtLeast(20.milliseconds)
                while (true) {
                    delay(step)
                    if (lastHeard.value.elapsedNow() > timeout) throw LinkLostException("Nothing heard for $timeout")
                }
            },
        )
        try {
            throw failure.await()
        } finally {
            // Closing the socket unblocks a read stuck in the OS, so the jobs can finish.
            socket.close()
            outbox.close()
            bulk.close()
            jobs.forEach { it.cancel() }
        }
    }
}

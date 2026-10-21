package io.github.l6desk.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration

/** [ByteSocket] over a java.net socket. Blocking calls run on [Dispatchers.IO]. */
class JavaByteSocket(private val socket: Socket) : ByteSocket {
    init {
        socket.tcpNoDelay = true // MIDI is small and latency-sensitive: don't batch packets
    }

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override val remoteAddress: String = socket.inetAddress?.hostAddress ?: "?"

    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) { input.read(buffer) }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        synchronized(output) {
            output.write(bytes)
            output.flush()
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/** Connects with java.net. */
object JavaSocketConnector : SocketConnector {
    override suspend fun connect(host: String, port: Int, timeout: Duration): ByteSocket = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), timeout.inWholeMilliseconds.toInt())
        } catch (e: Exception) {
            socket.close()
            throw e
        }
        JavaByteSocket(socket)
    }
}

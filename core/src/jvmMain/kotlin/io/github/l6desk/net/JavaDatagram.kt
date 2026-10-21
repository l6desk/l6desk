package io.github.l6desk.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import kotlin.time.Duration

/**
 * [DatagramChannel] over a java.net socket on an ordinary (ephemeral) port. Sending to the mDNS
 * group from here makes a legacy-unicast query, answered straight back to this socket.
 */
class JavaDatagramChannel private constructor(private val socket: DatagramSocket) : DatagramChannel {
    private val buffer = ByteArray(9000)

    override suspend fun send(packet: ByteArray, host: String, port: Int) = withContext(Dispatchers.IO) {
        socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(host), port))
    }

    override suspend fun receive(timeout: Duration): ByteArray? = withContext(Dispatchers.IO) {
        socket.soTimeout = timeout.inWholeMilliseconds.toInt().coerceAtLeast(1)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            socket.receive(packet)
            packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
        } catch (e: SocketTimeoutException) {
            null
        }
    }

    override fun close() {
        socket.close()
    }

    companion object {
        // A MulticastSocket only to set the hop limit mDNS expects (255); it never joins the group,
        // so Android needs no multicast lock.
        fun open(): DatagramChannel = JavaDatagramChannel(MulticastSocket(0).apply { timeToLive = 255 })
    }
}

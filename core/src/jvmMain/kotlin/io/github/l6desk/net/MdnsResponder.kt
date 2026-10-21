package io.github.l6desk.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException

/**
 * Announces the desktop's Bridge mode on the network ([BridgeAnnouncement]), so the apps find it
 * as they find the Pi, with no new dependency. It listens on the mDNS port alongside the computer's
 * own mDNS (Avahi on Ubuntu shares it), answers questions about `_l6desk-bridge._tcp`, and says
 * hello when started and goodbye when stopped.
 *
 * Legacy-unicast questions (the apps' own, from an ordinary port) are answered straight back to
 * the asker with its id and question; other questions get a multicast answer. Each answer carries
 * this computer's address on the asker's network, so a PC with several networks (Wi-Fi, Ethernet,
 * a VPN) hands out the one the tablet can reach.
 */
class MdnsResponder(private val announcement: BridgeAnnouncement, private val scope: CoroutineScope) {
    private var socket: MulticastSocket? = null
    private var job: Job? = null
    private val group = InetAddress.getByName(Mdns.GROUP)

    /** Starts answering; throws (e.g. the mDNS port can't be shared on this computer) if it can't. */
    fun start() {
        stop()
        val s = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(Mdns.PORT))
            timeToLive = 255
        }
        val joined = interfaces().count { ni -> runCatching { s.joinGroup(InetSocketAddress(group, Mdns.PORT), ni) }.isSuccess }
        if (joined == 0) {
            s.close()
            throw SocketException("No network to announce the bridge on")
        }
        socket = s
        job = scope.launch(Dispatchers.IO) {
            launch { repeat(2) { announce(BridgeAnnouncement.TTL); delay(1_000) } }
            val buffer = ByteArray(9000)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    s.receive(packet)
                } catch (e: SocketException) {
                    break // closed by stop()
                }
                runCatching { reply(s, packet) }
            }
        }
    }

    /** Says goodbye (so browsers forget it at once) and stops answering. */
    fun stop() {
        val s = socket ?: return
        runCatching { announce(0) }
        socket = null
        job?.cancel()
        job = null
        s.close()
    }

    private fun reply(s: MulticastSocket, packet: DatagramPacket) {
        val query = try {
            Mdns.parseQuery(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
        } catch (e: Mdns.MalformedException) {
            null
        } ?: return
        val asker = packet.address as? Inet4Address ?: return
        val address = addressFor(asker) ?: return
        val legacy = packet.port != Mdns.PORT
        val (answers, additional) = announcement.answer(query, address, if (legacy) BridgeAnnouncement.LEGACY_TTL else BridgeAnnouncement.TTL) ?: return
        val bytes = if (legacy) {
            Mdns.response(answers, additional, query.id, query.questions)
        } else {
            Mdns.response(answers, additional)
        }
        val unicast = legacy || query.questions.any { it.unicast }
        s.send(DatagramPacket(bytes, bytes.size, if (unicast) asker else group, if (unicast) packet.port else Mdns.PORT))
    }

    /** Hello ([ttl] > 0) or goodbye (0) on every network, each with this computer's address there. */
    private fun announce(ttl: Long) {
        val s = socket ?: return
        for (ni in interfaces()) {
            val address = ni.interfaceAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull() ?: continue
            val (answers, additional) = announcement.records(address.hostAddress, ttl)
            val bytes = Mdns.response(answers, additional)
            runCatching {
                s.networkInterface = ni
                s.send(DatagramPacket(bytes, bytes.size, group, Mdns.PORT))
            }
        }
    }

    companion object {
        /** Networks that can carry mDNS: up, multicast, not loopback, with an IPv4 address. */
        fun interfaces(): List<NetworkInterface> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { ni ->
                ni.isUp && ni.supportsMulticast() && !ni.isLoopback && ni.interfaceAddresses.any { it.address is Inet4Address }
            }
        }.getOrDefault(emptyList())

        /**
         * This computer's IPv4 address on [asker]'s network: the one whose subnet holds it (or the
         * asker's own address, when it's this computer). Null if none matches (e.g. a routed network).
         */
        fun addressFor(asker: Inet4Address): String? {
            val local = interfaces().flatMap { it.interfaceAddresses }.filter { it.address is Inet4Address }
            local.firstOrNull { it.address == asker }?.let { return it.address.hostAddress }
            return local.firstOrNull { sameSubnet(it.address as Inet4Address, asker, it.networkPrefixLength.toInt()) }?.address?.hostAddress
        }

        internal fun sameSubnet(a: Inet4Address, b: Inet4Address, prefix: Int): Boolean {
            if (prefix !in 0..32) return false
            val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
            fun int(x: Inet4Address) = x.address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
            return int(a) and mask == int(b) and mask
        }
    }
}

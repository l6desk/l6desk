package io.github.l6desk.net

/**
 * What the desktop's Bridge mode says about itself on the network (mDNS, `_l6desk-bridge._tcp`),
 * so the apps list it as they list the Pi: an [instance] name (shown in the app), the bridge's
 * [port], and a host name only this announcement uses ([host]), so it never clashes with the
 * computer's own name in Avahi or Windows.
 */
class BridgeAnnouncement(val instance: String, val port: Int, val host: String = hostFor(instance)) {
    private val service = BridgeDiscovery.SERVICE
    val fullName: String get() = "$instance.$service"

    /** The records to answer [query] with (sent to an asker in the network [address] belongs to), or null if it isn't about this bridge. */
    fun answer(query: Mdns.Query, address: String, ttl: Long = TTL): Pair<List<Mdns.Record>, List<Mdns.Record>>? {
        val asked = query.questions.filter { q ->
            val anyOrType = { t: Int -> q.type == t || q.type == Mdns.TYPE_ANY }
            (q.name.equals(service, ignoreCase = true) && anyOrType(Mdns.TYPE_PTR)) ||
                (q.name.equals(fullName, ignoreCase = true) && (anyOrType(Mdns.TYPE_SRV) || anyOrType(Mdns.TYPE_TXT))) ||
                (q.name.equals(host, ignoreCase = true) && anyOrType(Mdns.TYPE_A))
        }
        if (asked.isEmpty()) return null
        return records(address, ttl)
    }

    /** PTR as the answer, SRV, TXT and A as additional records (all a browser needs in one packet). */
    fun records(address: String, ttl: Long = TTL): Pair<List<Mdns.Record>, List<Mdns.Record>> =
        listOf<Mdns.Record>(Mdns.Record.Ptr(service, ttl, fullName)) to listOf(
            Mdns.Record.Srv(fullName, ttl, port, host),
            Mdns.Record.Txt(fullName, ttl, listOf("protocol=${BridgeProtocol.VERSION}", "kind=${BridgeInfo.KIND_DESKTOP}")),
            Mdns.Record.A(host, ttl, address),
        )

    companion object {
        /** Seconds; a legacy-unicast answer must not ask for more than 10 (RFC 6762, 6.7). */
        const val TTL = 120L
        const val LEGACY_TTL = 10L

        /** "BandPC" → "bandpc-l6desk.local": letters, digits and hyphens only. */
        fun hostFor(instance: String): String {
            val label = instance.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
                .replace(Regex("-+"), "-").trim('-').take(40).ifEmpty { "bridge" }
            return "$label-l6desk.local"
        }
    }
}

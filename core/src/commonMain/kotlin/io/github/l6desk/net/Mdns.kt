package io.github.l6desk.net

/**
 * Just enough multicast DNS (RFC 6762/6763) to find bridges, and for the desktop's Bridge mode to
 * announce itself: build and read questions, and build and read replies with PTR, SRV, TXT and A
 * records. Names are dot-separated without the trailing dot ("_l6desk-bridge._tcp.local").
 */
object Mdns {
    const val GROUP = "224.0.0.251"
    const val PORT = 5353

    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_SRV = 33
    const val TYPE_ANY = 255

    /** A question in a query. [unicast]: the asker wants the answer sent to it directly (the QU bit). */
    data class Question(val name: String, val type: Int, val unicast: Boolean = false)

    /** A query's id and questions. */
    data class Query(val id: Int, val questions: List<Question>)

    sealed interface Record {
        val name: String
        val ttl: Long

        data class Ptr(override val name: String, override val ttl: Long, val target: String) : Record
        data class Srv(override val name: String, override val ttl: Long, val port: Int, val target: String) : Record
        data class A(override val name: String, override val ttl: Long, val address: String) : Record
        data class Txt(override val name: String, override val ttl: Long, val entries: List<String>) : Record
    }

    class MalformedException(message: String) : Exception(message)

    /**
     * A one-question query. Sent from an ordinary (not 5353) port it is a "legacy unicast" query:
     * responders answer straight back to that port, so no multicast listening is needed.
     */
    fun query(name: String, type: Int, id: Int = 0): ByteArray {
        val out = mutableListOf<Byte>()
        fun u16(v: Int) { out += (v shr 8).toByte(); out += v.toByte() }
        u16(id); u16(0); u16(1); u16(0); u16(0); u16(0)
        for (label in name.split('.')) {
            val bytes = label.encodeToByteArray()
            require(bytes.size in 1..63) { "bad DNS label in $name" }
            out += bytes.size.toByte()
            out += bytes.toList()
        }
        out += 0
        u16(type); u16(1) // class IN
        return out.toByteArray()
    }

    /** The questions in a query, or null if [packet] is a response. */
    fun parseQuery(packet: ByteArray): Query? {
        val r = Reader(packet)
        val id = r.u16()
        val flags = r.u16()
        if (flags and 0x8000 != 0) return null
        val count = r.u16()
        r.skip(6)
        val questions = List(count) {
            val name = r.name()
            val type = r.u16()
            val cls = r.u16()
            Question(name, type, unicast = cls and 0x8000 != 0)
        }
        return Query(id, questions)
    }

    /**
     * A response holding [answers] (and [additional] records), each name written out in full.
     * For a legacy-unicast query, pass its [id] and [questions]: the asker expects them back.
     */
    fun response(answers: List<Record>, additional: List<Record> = emptyList(), id: Int = 0, questions: List<Question> = emptyList()): ByteArray {
        val out = mutableListOf<Byte>()
        fun u16(v: Int) { out += (v shr 8).toByte(); out += v.toByte() }
        fun u32(v: Long) { u16((v shr 16).toInt()); u16(v.toInt()) }
        fun name(n: String) {
            for (label in n.split('.')) {
                val bytes = label.encodeToByteArray().let { if (it.size > 63) it.copyOf(63) else it }
                out += bytes.size.toByte()
                out += bytes.toList()
            }
            out += 0
        }
        fun record(rec: Record) {
            name(rec.name)
            val type = when (rec) {
                is Record.A -> TYPE_A
                is Record.Ptr -> TYPE_PTR
                is Record.Srv -> TYPE_SRV
                is Record.Txt -> TYPE_TXT
            }
            u16(type)
            u16(if (rec is Record.Ptr) 1 else 0x8001) // class IN; unique records set "cache flush"
            u32(rec.ttl)
            val lengthAt = out.size
            u16(0)
            val start = out.size
            when (rec) {
                is Record.A -> rec.address.split('.').forEach { out += it.toInt().toByte() }
                is Record.Ptr -> name(rec.target)
                is Record.Srv -> { u16(0); u16(0); u16(rec.port); name(rec.target) }
                is Record.Txt -> rec.entries.ifEmpty { listOf("") }.forEach { e ->
                    val bytes = e.encodeToByteArray().let { if (it.size > 255) it.copyOf(255) else it }
                    out += bytes.size.toByte()
                    out += bytes.toList()
                }
            }
            val length = out.size - start
            out[lengthAt] = (length shr 8).toByte()
            out[lengthAt + 1] = length.toByte()
        }
        u16(id); u16(0x8400) // response, authoritative
        u16(questions.size); u16(answers.size); u16(0); u16(additional.size)
        for (q in questions) { name(q.name); u16(q.type); u16(1) }
        answers.forEach(::record)
        additional.forEach(::record)
        return out.toByteArray()
    }

    /** Every answer, authority and additional record of the types above; others are skipped. */
    fun parse(packet: ByteArray): List<Record> {
        val r = Reader(packet)
        r.u16() // id
        val flags = r.u16()
        if (flags and 0x8000 == 0) return emptyList() // a query, not a response
        val questions = r.u16()
        val records = r.u16() + r.u16() + r.u16()
        repeat(questions) { r.name(); r.skip(4) }
        val out = mutableListOf<Record>()
        repeat(records) {
            val name = r.name()
            val type = r.u16()
            r.u16() // class (top bit = cache flush)
            val ttl = r.u32()
            val length = r.u16()
            val start = r.pos
            when (type) {
                TYPE_PTR -> out += Record.Ptr(name, ttl, r.name())
                TYPE_SRV -> {
                    r.u16(); r.u16() // priority, weight
                    val port = r.u16()
                    out += Record.Srv(name, ttl, port, r.name())
                }
                TYPE_A -> if (length == 4) {
                    out += Record.A(name, ttl, (0 until 4).joinToString(".") { (packet[start + it].toInt() and 0xFF).toString() })
                }
                TYPE_TXT -> {
                    val entries = mutableListOf<String>()
                    var p = start
                    while (p < start + length) {
                        val n = packet[p].toInt() and 0xFF
                        if (n > 0) entries += packet.decodeToString(p + 1, minOf(p + 1 + n, start + length))
                        p += 1 + n
                    }
                    out += Record.Txt(name, ttl, entries)
                }
            }
            r.pos = start + length
        }
        return out
    }

    private class Reader(val b: ByteArray) {
        var pos = 0

        fun byte(at: Int): Int {
            if (at !in b.indices) throw MalformedException("truncated packet")
            return b[at].toInt() and 0xFF
        }

        fun u16(): Int = (byte(pos) shl 8 or byte(pos + 1)).also { pos += 2 }

        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        fun skip(n: Int) { pos += n }

        /** A possibly compressed name; leaves [pos] after it. */
        fun name(): String {
            val labels = mutableListOf<String>()
            var p = pos
            var end = -1
            var jumps = 0
            while (true) {
                val len = byte(p)
                when {
                    len == 0 -> { p++; break }
                    len and 0xC0 == 0xC0 -> {
                        if (++jumps > 20) throw MalformedException("name compression loop")
                        if (end < 0) end = p + 2
                        p = (len and 0x3F) shl 8 or byte(p + 1)
                    }
                    else -> {
                        byte(p + len) // bounds check
                        labels += b.decodeToString(p + 1, p + 1 + len)
                        p += 1 + len
                    }
                }
            }
            pos = if (end >= 0) end else p
            return labels.joinToString(".")
        }
    }
}

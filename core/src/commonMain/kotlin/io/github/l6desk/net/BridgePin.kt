package io.github.l6desk.net

/** The bridge PIN (protocol v6): 4 to 8 digits, answered as an HMAC so it never crosses the network. */
object BridgePin {
    val VALID = Regex("""\d{4,8}""")

    fun isValid(pin: String) = VALID.matches(pin)

    /** The answer to a bridge's challenge [nonce]. */
    fun answer(pin: String, nonce: ByteArray): ByteArray = hmacSha256(pin.encodeToByteArray(), nonce)

    /** Compares in constant time, so the time taken says nothing about how close a guess was. */
    fun matches(pin: String, nonce: ByteArray, mac: ByteArray): Boolean {
        val expected = answer(pin, nonce)
        if (mac.size != expected.size) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].toInt() xor mac[i].toInt())
        return diff == 0
    }
}

/** HMAC-SHA256 of [data] keyed with [key]. */
expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

/** [size] bytes from a cryptographically strong random source. */
expect fun secureRandomBytes(size: Int): ByteArray

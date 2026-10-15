package com.stockpile.volume.chain

import java.security.MessageDigest

object Hex {
    private val DIGITS = "0123456789ABCDEF".toCharArray()

    fun encode(bytes: ByteArray, upper: Boolean = true): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xFF
            out[i * 2] = DIGITS[v ushr 4]; out[i * 2 + 1] = DIGITS[v and 0x0F]
        }
        val s = String(out)
        return if (upper) s else s.lowercase()
    }

    fun decode(hex: String): ByteArray {
        val h = hex.trim()
        require(h.length % 2 == 0) { "odd hex length" }
        return ByteArray(h.length / 2) { ((Character.digit(h[it * 2], 16) shl 4) or Character.digit(h[it * 2 + 1], 16)).toByte() }
    }

    /** UTF-8 text as upper-case hex, the format XRPL memos use. */
    fun utf8(text: String): String = encode(text.toByteArray(Charsets.UTF_8))

    fun utf8FromHex(hex: String): String = String(decode(hex), Charsets.UTF_8)

    /** Lower-case hex SHA-256 (same output as `sha256sum`). */
    fun sha256(bytes: ByteArray): String = encode(MessageDigest.getInstance("SHA-256").digest(bytes), upper = false)
}

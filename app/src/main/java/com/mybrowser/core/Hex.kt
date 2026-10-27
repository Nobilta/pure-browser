package com.mybrowser.core

import java.security.MessageDigest

/** Hex and digest helpers shared by downloads, filter digests and checkpoint sidecars. */
internal object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    /**
     * Lower-case hex SHA-256 of [data].
     *
     * A lookup table instead of `"%02x".format(byte)`: filter payloads and resumable
     * downloads hash megabytes at a time, where one String allocation per byte dominates.
     */
    fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val value = digest[i].toInt() and 0xff
            out[i * 2] = DIGITS[value ushr 4]
            out[i * 2 + 1] = DIGITS[value and 0x0f]
        }
        return String(out)
    }
}

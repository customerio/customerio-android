package io.customer.messagingpush.livenotification

import java.security.SecureRandom

/**
 * Generates canonical uppercase [ULID](https://github.com/ulid/spec) identifiers
 * (26-char Crockford Base32) for locally-started live notifications.
 */
internal object ULID {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private val secureRandom = SecureRandom()

    /** Generate a new 26-character uppercase Crockford Base32 ULID. */
    fun generate(timestampMillis: Long = System.currentTimeMillis()): String =
        encodeTimestamp(clampToRange(timestampMillis)) + encodeRandomness(randomBytes())

    private fun clampToRange(millis: Long): Long {
        if (millis <= 0L) return 0L
        val max = (1L shl 48) - 1L
        return if (millis > max) max else millis
    }

    private fun encodeTimestamp(timestamp: Long): String {
        val builder = StringBuilder(10)
        for (index in 0 until 10) {
            val shift = (9 - index) * 5
            val value = ((timestamp shr shift) and 0x1F).toInt()
            builder.append(ALPHABET[value])
        }
        return builder.toString()
    }

    private fun randomBytes(): ByteArray = ByteArray(10).also(secureRandom::nextBytes)

    private fun encodeRandomness(bytes: ByteArray): String {
        val builder = StringBuilder(16)
        for (group in 0 until 16) {
            var value = 0
            for (offset in 0 until 5) {
                val bitIndex = group * 5 + offset
                val byte = bytes[bitIndex / 8].toInt() and 0xFF
                val bit = (byte shr (7 - (bitIndex % 8))) and 1
                value = (value shl 1) or bit
            }
            builder.append(ALPHABET[value])
        }
        return builder.toString()
    }

    /** Decode the millisecond timestamp from a ULID, or `null` if malformed. */
    fun timestampMillis(ulid: String): Long? {
        if (ulid.length != 26) return null
        var timestamp = 0L
        for (character in ulid.take(10)) {
            val index = ALPHABET.indexOf(character)
            if (index < 0) return null
            timestamp = (timestamp shl 5) or index.toLong()
        }
        return timestamp
    }
}

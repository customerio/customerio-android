package io.customer.messagingpush.livenotification

import java.security.SecureRandom

/**
 * Generates [ULID](https://github.com/ulid/spec) identifiers for locally-started
 * live notifications.
 *
 * A ULID is a 128-bit, lexicographically sortable identifier:
 * - the most-significant 48 bits are a Unix-epoch **millisecond** timestamp, and
 * - the remaining 80 bits are cryptographic randomness.
 *
 * It is rendered as a 26-character, **UPPERCASE** Crockford Base32 string: the first 10
 * characters encode the timestamp and the last 16 encode the randomness. Because
 * `26 * 5 = 130` bits but the value is only 128 bits, the leading character carries just
 * 3 significant timestamp bits, so it is always in the range `0`-`7`.
 *
 * The output is canonical uppercase; the backend expects the canonical form. This mirrors
 * the iOS SDK's ULID minting so both platforms share one id format.
 */
internal object ULID {
    /** Crockford Base32 alphabet (excludes `I`, `L`, `O`, and `U` to avoid ambiguity). */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private val secureRandom = SecureRandom()

    /** Generate a new 26-character uppercase Crockford Base32 ULID. */
    fun generate(timestampMillis: Long = System.currentTimeMillis()): String =
        encodeTimestamp(clampToRange(timestampMillis)) + encodeRandomness(randomBytes())

    /** Clamp into the representable 48-bit ULID timestamp range. */
    private fun clampToRange(millis: Long): Long {
        if (millis <= 0L) return 0L
        val max = (1L shl 48) - 1L
        return if (millis > max) max else millis
    }

    /** Encode the 48-bit timestamp as the first 10 characters, most-significant 5 bits first. */
    private fun encodeTimestamp(timestamp: Long): String {
        val builder = StringBuilder(10)
        for (index in 0 until 10) {
            val shift = (9 - index) * 5
            val value = ((timestamp shr shift) and 0x1F).toInt()
            builder.append(ALPHABET[value])
        }
        return builder.toString()
    }

    /** 10 cryptographically random bytes (80 bits). */
    private fun randomBytes(): ByteArray = ByteArray(10).also(secureRandom::nextBytes)

    /**
     * Encode 10 bytes (80 bits) as the last 16 characters. The bytes are read as one
     * big-endian bit stream split into 16 groups of 5 bits; `16 * 5 == 80`, so there is
     * no padding. Bytes are masked with `0xFF` because Kotlin's `Byte` is signed.
     */
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

    /**
     * Decode the millisecond timestamp from the first 10 characters of a ULID, or `null`
     * if the input is malformed. Test/verification support.
     */
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

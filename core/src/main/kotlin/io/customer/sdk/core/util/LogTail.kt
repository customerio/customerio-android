package io.customer.sdk.core.util

import io.customer.base.internal.InternalCustomerIOApi

/**
 * Builds the key tail a log line carries after its sentence: ` || ev=<event> key=value …`.
 * Tools such as cio-sdk-tools `verify push` read the tail, so the sentence in front stays free to
 * change. Never rename an `ev` or a key, or change what a value means; adding a key is fine.
 *
 * A parser splits the line on the **last** [DELIMITER], then each pair at its first `=`, then decodes
 * the value. [encode] keeps `|` and whitespace out of values, so no value can break either split.
 */
@InternalCustomerIOApi
object LogTail {
    const val DELIMITER = " || "

    /**
     * @param ev stable event name, e.g. `push.opened`.
     * @param fields lowercase `[a-z_]+` keys, written in the given order; null values are omitted.
     */
    fun build(ev: String, vararg fields: Pair<String, String?>): String {
        val tail = StringBuilder(DELIMITER).append("ev=").append(encode(ev))
        for ((key, value) in fields) {
            if (value == null) continue
            tail.append(' ').append(key).append('=').append(encode(value))
        }
        return tail.toString()
    }

    /**
     * Percent-encodes `%`, `|` and every whitespace character as UTF-8 `%XX` (uppercase hex).
     * Everything else stays literal, `=`, `,` and `:` included, since delivery ids can contain `=`.
     */
    fun encode(value: String): String {
        if (value.none(::needsEncoding)) return value

        val encoded = StringBuilder(value.length + 8)
        for (character in value) {
            if (!needsEncoding(character)) {
                encoded.append(character)
                continue
            }
            // Whitespace is all in the BMP, so a single char is never half of a surrogate pair here.
            for (byte in character.toString().toByteArray(Charsets.UTF_8)) {
                val bits = byte.toInt() and 0xFF
                encoded.append('%').append(HEX_DIGITS[bits shr 4]).append(HEX_DIGITS[bits and 0x0F])
            }
        }
        return encoded.toString()
    }

    private fun needsEncoding(character: Char): Boolean =
        character == '%' || character == '|' || character.isWhitespace()

    private const val HEX_DIGITS = "0123456789ABCDEF"
}

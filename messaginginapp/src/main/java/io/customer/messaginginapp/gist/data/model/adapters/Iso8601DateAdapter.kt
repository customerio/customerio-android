package io.customer.messaginginapp.gist.data.model.adapters

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Gson TypeAdapter for parsing ISO 8601 date strings to Date objects.
 * Handles formats with milliseconds, microseconds, or no fractional seconds.
 * Gracefully handles null/invalid values by returning null instead of throwing.
 *
 * Note: Java's Date only supports millisecond precision. Microseconds are truncated to milliseconds.
 */
internal class Iso8601DateAdapter : TypeAdapter<Date?>() {
    override fun write(out: JsonWriter, value: Date?) {
        val formatter = formatterWithMillis.get()
        if (value == null || formatter == null) {
            out.nullValue()
        } else {
            out.value(formatter.format(value))
        }
    }

    override fun read(input: JsonReader): Date? {
        if (input.peek() == JsonToken.NULL) {
            input.nextNull()
            return null
        }

        val dateString = input.nextString()
        if (dateString.isBlank()) {
            return null
        }

        // Try parsing with milliseconds first
        return try {
            // Normalize microseconds to milliseconds if present
            // e.g., "2026-01-27T12:30:45.123456Z" -> "2026-01-27T12:30:45.123Z"
            val normalizedDateString = normalizeFractionalSeconds(dateString)
            formatterWithMillis.get()?.parse(normalizedDateString)
        } catch (_: Exception) {
            // Try parsing without milliseconds
            try {
                formatterWithoutMillis.get()?.parse(dateString)
            } catch (_: Exception) {
                // If both fail, return null instead of throwing
                // This makes the API resilient to unexpected date formats
                null
            }
        }
    }

    /**
     * Truncates fractional seconds to 3 digits (milliseconds) if longer.
     * Handles microseconds (6 digits) or other precision levels from the server.
     */
    private fun normalizeFractionalSeconds(dateString: String): String {
        // Pattern: digits after decimal point before 'Z'
        val regex = """(\.\d{3})\d+Z""".toRegex()
        return regex.replace(dateString) { matchResult ->
            // Keep only first 3 digits (milliseconds) + Z
            "${matchResult.groupValues[1]}Z"
        }
    }

    companion object {
        // ThreadLocal caches formatters per thread to avoid creating new instances repeatedly
        // while still being thread-safe (SimpleDateFormat is not thread-safe)
        private val formatterWithMillis = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat {
                return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
            }
        }

        private val formatterWithoutMillis = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat {
                return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
            }
        }
    }
}

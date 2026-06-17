package io.customer.sdk.core.util

import io.customer.base.internal.InternalCustomerIOApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Produces the ISO-8601 timestamp string the Pipelines (CDP) `/track` endpoint expects in its
 * reserved top-level `timestamp` field; a bare integer there is read as milliseconds, so the string
 * form is sent instead. Matches the format Segment emits, since the analytics pipeline delivers the
 * same events to the same endpoint.
 */
@InternalCustomerIOApi
object Iso8601TimestampFormatter {
    // ThreadLocal avoids the synchronization overhead of SimpleDateFormat, which
    // is not thread-safe and must not be shared between threads.
    private val formatters = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'.'SSSzzz", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }

    /** Seconds-precision input; the `.SSS` slot is always `000`. Returns null if formatting fails. */
    fun fromUnixSeconds(unixSeconds: Long): String? = runCatching {
        val formatter = formatters.get() ?: return null
        formatter.format(Date(TimeUnit.SECONDS.toMillis(unixSeconds))).replace("UTC", "Z")
    }.getOrNull()

    /** Preserves millisecond precision, unlike [fromUnixSeconds]. Returns null if formatting fails. */
    fun fromDate(date: Date): String? = runCatching {
        val formatter = formatters.get() ?: return null
        formatter.format(date).replace("UTC", "Z")
    }.getOrNull()
}

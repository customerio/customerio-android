package io.customer.datapipelines.util

import com.segment.analytics.kotlin.core.utilities.SegmentInstant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Class used to format timestamp to match the format of [SegmentInstant] to ensure consistency.
 * Any future changes to the format of [SegmentInstant] should be reflected here to avoid any
 * inconsistencies.
 */
internal class SegmentInstantFormatter {
    companion object {
        // ThreadLocal is used to avoid the synchronization overhead of SimpleDateFormat.
        // SimpleDateFormat is not thread-safe and should not be shared between threads.
        private val formatters = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat {
                return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'.'SSSzzz", Locale.ROOT).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
            }
        }

        /**
         * This function is helpful to convert a unix timestamp to a string in the format similar to
         * output of [SegmentInstant.now].
         *
         * @param unixTimestamp The unix timestamp to convert.
         * @return Formatted string in the format similar to output of [SegmentInstant.now].
         * If the conversion fails, it will return null.
         */
        fun from(unixTimestamp: Long): String? = runCatching {
            val formatter = formatters.get() ?: return null
            val date = Date()
            date.time = TimeUnit.SECONDS.toMillis(unixTimestamp)
            return formatter.format(date).replace("UTC", "Z")
        }.getOrNull()
    }
}

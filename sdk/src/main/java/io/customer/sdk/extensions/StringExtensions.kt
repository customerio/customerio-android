/* ktlint-disable filename */ // until this extension file contains 2+ functions in it, we will disable this ktlint rule.
package io.customer.sdk.extensions

import io.customer.sdk.data.model.Region
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.util.CioLogLevel

fun String.getScreenNameFromActivity(): String {
    val regex = Regex(
        pattern = "Activity|ListActivity|FragmentActivity|DialogActivity",
        option = RegexOption.IGNORE_CASE
    )
    return this.replace(regex, "")
}

fun String?.toRegion(fallback: Region = Region.US): Region {
    return if (this.isNullOrBlank()) {
        fallback
    } else {
        listOf(
            Region.US,
            Region.EU
        ).find { value -> value.code.equals(this, ignoreCase = true) } ?: fallback
    }
}

fun String?.toMetricEvent(): MetricEvent? {
    return if (this.isNullOrBlank()) {
        null
    } else {
        MetricEvent.values()
            .find { value -> value.name.equals(this, ignoreCase = true) }
    }
}

fun String?.toCIOLogLevel(fallback: CioLogLevel = CioLogLevel.NONE): CioLogLevel {
    return CioLogLevel.values().find { value -> value.name.equals(this, ignoreCase = true) }
        ?: fallback
}

fun String.takeIfNotBlank(): String? = takeIf { it.isNotBlank() }

/* ktlint-disable filename */ // until this extension file contains 2+ functions in it, we will disable this ktlint rule.
package io.customer.sdk.extensions

import io.customer.sdk.data.model.Region

fun String.getScreenNameFromActivity(): String {
    val regex = Regex(
        pattern = "Activity|ListActivity|FragmentActivity|DialogActivity",
        option = RegexOption.IGNORE_CASE
    )
    return this.replace(regex, "")
}

internal fun String?.toRegion(fallback: Region = Region.US): Region {
    return if (this.isNullOrBlank()) fallback
    else listOf(
        Region.US,
        Region.EU
    ).find { value -> value.code.equals(this, ignoreCase = true) } ?: fallback
}

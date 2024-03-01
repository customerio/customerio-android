/* ktlint-disable filename */ // until this extension file contains 2+ functions in it, we will disable this ktlint rule.
package io.customer.sdk.extensions

fun String.getScreenNameFromActivity(): String {
    val regex = Regex(
        pattern = "Activity|ListActivity|FragmentActivity|DialogActivity",
        option = RegexOption.IGNORE_CASE
    )
    return this.replace(regex, "")
}

fun String.takeIfNotBlank(): String? = takeIf { it.isNotBlank() }

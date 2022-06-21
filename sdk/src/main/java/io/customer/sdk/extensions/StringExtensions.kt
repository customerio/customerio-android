// ktlint-disable filename
package io.customer.sdk.extensions

fun String.getScreenNameFromActivity(): String {
    val regex = Regex(
        pattern = "Activity|ListActivity|FragmentActivity|DialogActivity",
        option = RegexOption.IGNORE_CASE
    )
    return this.replace(regex, "")
}

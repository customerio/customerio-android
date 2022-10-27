package io.customer.sdk.util

import io.customer.base.extenstions.getUnixTimestamp
import java.util.*

/**
 * Exists to make test functions easier to write since we can mock the date.
 */
interface DateUtil {
    val now: Date
    val nowUnixTimestamp: Long
}

internal class DateUtilImpl : DateUtil {
    override val now: Date
        get() = Date()

    override val nowUnixTimestamp: Long
        get() = this.now.getUnixTimestamp()
}

package io.customer.common_test

import io.customer.base.extenstions.unixTimeToDate
import io.customer.sdk.util.DateUtil
import java.util.*

/**
 * Convenient alternative to mocking [DateUtil] in your test since the code is boilerplate.
 */
class DateUtilStub : DateUtil {
    // modify this value in your test class if you need to.
    var givenDateMillis = 1646238885L

    val givenDate: Date
        get() = givenDateMillis.unixTimeToDate()

    override val now: Date
        get() = givenDate

    override val nowUnixTimestamp: Long
        get() = givenDateMillis
}

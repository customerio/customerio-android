package io.customer.sdk.commontest

import io.customer.base.extenstions.getUnixTimestamp
import io.customer.sdk.util.DateUtil
import java.util.*

/**
 * Convenient alternative to mocking [DateUtil] in your test since the code is boilerplate.
 */
class DateUtilStub : DateUtil {

    // modify this value in your test class if you need to.
    var givenDate: Date = Date(1646238885L)

    override val now: Date
        get() = givenDate

    override val nowUnixTimestamp: Long
        get() = now.getUnixTimestamp()
}

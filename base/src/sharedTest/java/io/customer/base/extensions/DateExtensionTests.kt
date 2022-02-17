package io.customer.base.extensions

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.base.extenstions.unixTimeToDate
import io.customer.base.testutils.BaseTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.*

@RunWith(AndroidJUnit4::class)
class DateExtensionTests : BaseTest() {

    private val givenDate: Date
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").parse("2001-07-04T12:08:56.235+0000")!!

    private val givenDateWithoutMillis: Date
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").parse("2001-07-04T12:08:56.000+0000")!!

    private val givenDateMillis: Long
        get() = 994248536

    @Test
    fun getUnixTimestamp_givenDate_expectMillisNoDecimalPoint() {
        val expected = givenDateMillis
        val actual = givenDate.getUnixTimestamp()

        actual shouldBeEqualTo expected
    }

    @Test
    fun unixTimeToDate_givenMillis_expectGetDate() {
        val expected = givenDateWithoutMillis
        val actual = givenDateMillis.unixTimeToDate()

        actual shouldBeEqualTo expected
    }
}

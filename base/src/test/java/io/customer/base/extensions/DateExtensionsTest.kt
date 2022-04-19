package io.customer.base.extensions

import io.customer.base.extenstions.add
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.base.extenstions.hasPassed
import io.customer.base.extenstions.subtract
import io.customer.base.extenstions.unixTimeToDate
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class DateExtensionsTest {

    fun getDateFromIso8601(parseString: String): Date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZZZ").parse(parseString)

    private val oldDate: Date = getDateFromIso8601("2000-01-05T01:00:00+00:00")
    private val oldDateUnixTimestamp: Long = 947055600

    @Test
    fun getUnixTimestamp_givenDate_expectToGetTimestampForDate() {
        val expected = oldDateUnixTimestamp
        val actual = oldDate.getUnixTimestamp()

        expected shouldBeEqualTo actual
    }

    @Test
    fun unixTimeToDate_givenUnixTime_expectGetDate() {
        val expected = oldDate
        val actual = oldDateUnixTimestamp.unixTimeToDate()

        actual shouldBeEqualTo expected
    }

    @Test
    fun add_givenAdd1Day_expectGetDate1DayInFuture() {
        val expected = getDateFromIso8601("2000-01-06T01:00:00+00:00")
        val actual = oldDate.add(1, TimeUnit.DAYS)

        actual shouldBeEqualTo expected
    }

    @Test
    fun subtract_givenSubtract1Day_expectGetDate1DayInPast() {
        val expected = getDateFromIso8601("2000-01-04T01:00:00+00:00")
        val actual = oldDate.subtract(1, TimeUnit.DAYS)

        actual shouldBeEqualTo expected
    }

    @Test
    fun hasPassed_givenDateInThePast_expectTrue() {
        oldDate.hasPassed() shouldBeEqualTo true
    }

    @Test
    fun hasPassed_givenDateInFuture_expectFalse() {
        Date().add(1, TimeUnit.MINUTES).hasPassed() shouldBeEqualTo false
    }
}

package io.customer.base.extensions

import io.customer.base.extenstions.add
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.base.extenstions.hasPassed
import io.customer.base.extenstions.isOlderThan
import io.customer.base.extenstions.subtract
import io.customer.base.extenstions.unixTimeToDate
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class DateExtensionsTest {

    fun getDateFromIso8601(parseString: String): Date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZZZ").parse(parseString)

    // make sure to use a recent date. a hard-coded date in the year 2000 used to be used but it gave false positives in the tests.
    // Changed to a more modern time and tests began failing.
    private val oldDate: Date = getDateFromIso8601("2022-04-19T21:17:41+0000")
    private val oldDateUnixTimestamp: Long = 1650403061

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
        val expected = getDateFromIso8601("2022-04-20T21:17:41+0000")
        val actual = oldDate.add(1, TimeUnit.DAYS)

        actual shouldBeEqualTo expected
    }

    @Test
    fun subtract_givenSubtract1Day_expectGetDate1DayInPast() {
        val expected = getDateFromIso8601("2022-04-18T21:17:41+0000")
        val actual = oldDate.subtract(1, TimeUnit.DAYS)

        actual shouldBeEqualTo expected
    }

    @Test
    fun hasPassed_givenDateInThePast_expectTrue() {
        Date().subtract(1, TimeUnit.MINUTES).hasPassed() shouldBeEqualTo true
    }

    @Test
    fun hasPassed_givenDateInFuture_expectFalse() {
        Date().add(1, TimeUnit.MINUTES).hasPassed() shouldBeEqualTo false
    }

    @Test
    fun isOlderThan_givenDateThatIsOlder_expectTrue() {
        Date().subtract(2, TimeUnit.DAYS).isOlderThan(Date().subtract(1, TimeUnit.DAYS)).shouldBeTrue()
    }

    @Test
    fun isOlderThan_givenDateThatIsNewer_expectFalse() {
        Date().subtract(1, TimeUnit.DAYS).isOlderThan(Date().subtract(2, TimeUnit.DAYS)).shouldBeFalse()
    }
}

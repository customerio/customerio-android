package io.customer.sdk.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commonTest.BaseTest
import io.customer.sdk.util.Seconds
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HttpRetryPolicyTest : BaseTest() {

    private lateinit var retryPolicy: CustomerIOApiRetryPolicy

    @Before
    override fun setup() {
        super.setup()

        retryPolicy = CustomerIOApiRetryPolicy()
    }

    @Test
    fun nextSleepTime_expectGetRetriesUntilRunsOut() {
        val expected = CustomerIOApiRetryPolicy.retryPolicy
        val actual: MutableList<Seconds> = mutableListOf()

        var moreTimeToGet = true
        while (moreTimeToGet) {
            val nextSleepTime = retryPolicy.nextSleepTime
            if (nextSleepTime == null) moreTimeToGet = false
            else actual.add(nextSleepTime)
        }

        expected shouldBeEqualTo actual
    }

    @Test
    fun nextSleepTime_givenCallManyTimes_expectGetNullAfterRunOutOfRetries() {
        val expectedTimesToGetNull = 10
        var numberOfTimesToCall = CustomerIOApiRetryPolicy.retryPolicy.count() + expectedTimesToGetNull
        var actualTimesGotNull = 0

        while (numberOfTimesToCall > 0) {
            val nextSleepTime = retryPolicy.nextSleepTime
            if (nextSleepTime == null) actualTimesGotNull += 1

            numberOfTimesToCall -= 1
        }

        actualTimesGotNull shouldBeEqualTo expectedTimesToGetNull
    }

    @Test
    fun reset_givenGetNextSleepTime_expectRetriesToResetAndContinueGettingNextSleepTime() {
        val expectedFirstSleepTime = CustomerIOApiRetryPolicy.retryPolicy[0]
        val expectedSecondSleepTime = CustomerIOApiRetryPolicy.retryPolicy[1]

        expectedFirstSleepTime shouldNotBeEqualTo expectedSecondSleepTime

        retryPolicy.nextSleepTime shouldBeEqualTo expectedFirstSleepTime
        retryPolicy.nextSleepTime shouldBeEqualTo expectedSecondSleepTime

        retryPolicy.reset()

        retryPolicy.nextSleepTime shouldBeEqualTo expectedFirstSleepTime
        retryPolicy.nextSleepTime shouldBeEqualTo expectedSecondSleepTime
    }
}

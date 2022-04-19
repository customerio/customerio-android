package io.customer.sdk.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.common_test.AsyncWait
import io.customer.common_test.BaseTest
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimpleTimerTest : BaseTest() {

    private lateinit var timer: AndroidSimpleTimer

    @Before
    override fun setup() {
        super.setup()

        timer = AndroidSimpleTimer(di.logger)
    }

    @Test
    fun scheduleIfNotAlready_givenCallMultipleTimes_expectIgnoreFutureRequests() {
        val expectation = AsyncWait(1)
        var didSchedule = timer.scheduleIfNotAlready(Seconds(0.1)) {
            expectation.fulfill()
        }
        didSchedule.shouldBeTrue()

        didSchedule = timer.scheduleIfNotAlready(Seconds(0.01)) {
            expectation.fulfill() // this should not fire
        }
        didSchedule.shouldBeFalse()

        expectation.wait()
    }

    @Test
    fun scheduleIfNotAlready_givenCallAfterTimerFires_expectStartNewTimer() {
        var expectation = AsyncWait(1)
        var didSchedule = timer.scheduleIfNotAlready(Seconds(0.1)) {
            expectation.fulfill()
        }
        didSchedule.shouldBeTrue()
        expectation.wait()

        expectation = AsyncWait(1)
        didSchedule = timer.scheduleIfNotAlready(Seconds(0.1)) {
            expectation.fulfill()
        }
        didSchedule.shouldBeTrue()
        expectation.wait()
    }

    @Test
    fun scheduleAndCancelPrevious_givenPreviouslyRunningTimer_expectCancelAndStartNew() {
        val expectNoFire = AsyncWait(1, isInverted = true)
        timer.scheduleAndCancelPrevious(Seconds(0.1)) {
            expectNoFire.fulfill()
        }

        timer.cancel()

        val expectFire = AsyncWait(1)
        timer.scheduleAndCancelPrevious(Seconds(0.2)) { // be a slightly larger value then the previous timer to give it a chance to finish if its going to.
            expectFire.fulfill()
        }

        expectFire.wait()
    }

    @Test
    fun scheduleAndCancelPreviousSuspend_givenPreviouslyRunningTimer_expectCancelAndStartNew() = runBlocking {
        val expectNoFire = AsyncWait(1, isInverted = true)
        timer.scheduleAndCancelPreviousSuspend(Seconds(0.1)) {
            expectNoFire.fulfill()
        }

        timer.cancel()

        val expectFire = AsyncWait(1)
        timer.scheduleAndCancelPreviousSuspend(Seconds(0.2)) { // be a slightly larger value then the previous timer to give it a chance to finish if its going to.
            expectFire.fulfill()
        }

        expectFire.wait()
    }

    @Test
    fun scheduleAndCancelPrevious_givenCallMultipleTimes_expectScheduleNewTimerEachTime() {
        AsyncWait(1).also { asyncWait ->
            timer.scheduleAndCancelPrevious(Seconds(0.1)) {
                asyncWait.fulfill()
            }
            asyncWait.wait()
        }

        AsyncWait(1).also { asyncWait ->
            timer.scheduleAndCancelPrevious(Seconds(0.1)) {
                asyncWait.fulfill()
            }
            asyncWait.wait()
        }
    }

    @Test
    fun scheduleAndCancelPreviousSuspend_givenCallMultipleTimes_expectScheduleNewTimerEachTime(): Unit = runBlocking {
        AsyncWait(1).also { asyncWait ->
            timer.scheduleAndCancelPreviousSuspend(Seconds(0.1)) {
                asyncWait.fulfill()
            }
            asyncWait.wait()
        }

        AsyncWait(1).also { asyncWait ->
            timer.scheduleAndCancelPreviousSuspend(Seconds(0.1)) {
                asyncWait.fulfill()
            }
            asyncWait.wait()
        }
    }

    @Test
    fun cancel_givenNoScheduleScheduled_expectNoErrors() {
        timer.cancel()
    }

    @Test
    fun cancel_givenScheduled_expectTimerCanceled() {
        val expect = AsyncWait(1, isInverted = true)
        val didSchedule = timer.scheduleIfNotAlready(Seconds(0.1)) {
            expect.fulfill()
        }
        didSchedule.shouldBeTrue()

        timer.cancel()

        expect.wait()
    }
}

package io.customer.sdk.util

import androidx.test.annotation.UiThreadTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.common_test.BaseTest
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests must be executed on Android device, only. Running on robolectric gave false positive results.
 */
@RunWith(AndroidJUnit4::class)
class SimpleTimerTest : BaseTest() {

    private lateinit var timer: AndroidSimpleTimer

    @Before
    override fun setup() {
        super.setup()

        timer = AndroidSimpleTimer(di.logger, testDispatcher)
    }

    @After
    override fun teardown() {
        super.teardown()

        // prevent timer from actually running and causing bad behavior in tests
        timer.cancel()
    }

    @UiThreadTest
    @Test
    fun scheduleIfNotAlready_givenCallMultipleTimes_expectIgnoreFutureRequests() {
        var didSchedule = timer.scheduleIfNotAlready(Seconds(1.0)) {}
        didSchedule.shouldBeTrue()

        didSchedule = timer.scheduleIfNotAlready(Seconds(1.0)) {}
        didSchedule.shouldBeFalse()
    }

    @UiThreadTest
    @Test
    fun scheduleIfNotAlready_givenCallAfterTimerFires_expectStartNewTimer() {
        var calledCallback = false
        var didSchedule = timer.scheduleIfNotAlready(Seconds(0.0)) {
            calledCallback = true
        }
        didSchedule.shouldBeTrue()
        calledCallback.shouldBeTrue()
        calledCallback = false

        didSchedule = timer.scheduleIfNotAlready(Seconds(0.0)) {
            calledCallback = true
        }
        didSchedule.shouldBeTrue()
        calledCallback = true
    }

    @UiThreadTest
    @Test
    fun scheduleAndCancelPrevious_givenPreviouslyRunningTimer_expectCancelAndStartNew() {
        timer.scheduleAndCancelPrevious(Seconds(1.0)) {} // give a time > 0 so it will start running but not finish by time we cancel

        var didCallTimerAfterCancel = false
        timer.scheduleAndCancelPrevious(Seconds(0.0)) {
            didCallTimerAfterCancel = true
        }

        didCallTimerAfterCancel.shouldBeTrue()
    }

    @Test
    fun cancel_givenNoScheduleScheduled_expectNoErrors() {
        timer.cancel()
    }

    @UiThreadTest
    @Test
    fun cancel_givenCallAfterTimerStarts_expectCancelsTimerAndStartsNew() {
        var didSchedule = timer.scheduleIfNotAlready(Seconds(1.0)) {} // provide a value > 0 to start timer but not finish by time we cancel timer
        didSchedule.shouldBeTrue()

        timer.cancel()

        var didRunTimerAfterCancel = false
        didSchedule = timer.scheduleIfNotAlready(Seconds(0.0)) {
            didRunTimerAfterCancel = true
        }
        didSchedule.shouldBeTrue()
        didRunTimerAfterCancel.shouldBeTrue()
    }
}

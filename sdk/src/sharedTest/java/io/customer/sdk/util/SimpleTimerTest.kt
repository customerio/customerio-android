package io.customer.sdk.util

/**
 * Commenting out test for now as tests are difficult to write for a Timer.
 *
 * The functions of SimpleTimer call a given lambda after X amount of time. These tests are easy to write if you can
 * have the test function wait until the given lambda gets executed or not. I have not been able to do this in a test function.
 *
 * I think the best method forward is to (1) QA test the timer in an Android app. See if the timer actually waits 30 seconds.
 * (2) re-think how tests for the timer are made. There is logic in the timer that's important to test, but it might not be
 * possible to test in an asynchronous way. We might need to make all test code run synchronously.
 */

// import androidx.test.annotation.UiThreadTest
// import androidx.test.ext.junit.runners.AndroidJUnit4
// import io.customer.common_test.AsyncWait
// import io.customer.common_test.BaseTest
// import kotlinx.coroutines.runBlocking
// import org.amshove.kluent.shouldBeFalse
// import org.amshove.kluent.shouldBeTrue
// import org.junit.Before
// import org.junit.Test
// import org.junit.runner.RunWith
// import kotlin.concurrent.thread
//
// @RunWith(AndroidJUnit4::class)
// class SimpleTimerTest : BaseTest() {
//
//    private lateinit var timer: AndroidSimpleTimer
//
//    @Before
//    override fun setup() {
//        super.setup()
//
//        timer = AndroidSimpleTimer(di.logger)
//    }
//
//    @UiThreadTest
//    @Test
//    fun scheduleIfNotAlready_givenCallMultipleTimes_expectIgnoreFutureRequests() {
//        val expectation = AsyncWait(1)
//        var didSchedule = timer.scheduleIfNotAlready(Seconds(0.1)) {
//            expectation.fulfill()
//        }
//        didSchedule.shouldBeTrue()
//
//        didSchedule = timer.scheduleIfNotAlready(Seconds(0.01)) {
//            expectation.fulfill() // this should not fire
//        }
//        didSchedule.shouldBeFalse()
//
//        expectation.wait()
//    }
//
//    @UiThreadTest
//    @Test
//    fun scheduleIfNotAlready_givenCallAfterTimerFires_expectStartNewTimer() {
//        var calledCallback = false
//        var didSchedule = timer.scheduleIfNotAlready(Seconds(0.0)) {
//            calledCallback = true
//        }
//        didSchedule.shouldBeTrue()
//        calledCallback.shouldBeTrue()
//        calledCallback = false
//
//        didSchedule = timer.scheduleIfNotAlready(Seconds(0.0)) {
//            calledCallback = true
//        }
//        didSchedule.shouldBeTrue()
//        calledCallback = true
//    }
//
//    @UiThreadTest
//    @Test
//    fun scheduleAndCancelPrevious_givenPreviouslyRunningTimer_expectCancelAndStartNew() {
//        val expectNoFire = AsyncWait(1, isInverted = true)
//        timer.scheduleAndCancelPrevious(Seconds(0.1)) {
//            expectNoFire.fulfill()
//        }
//
//        timer.cancel()
//
//        val expectFire = AsyncWait(1)
//        timer.scheduleAndCancelPrevious(Seconds(0.2)) { // be a slightly larger value then the previous timer to give it a chance to finish if its going to.
//            expectFire.fulfill()
//        }
//
//        expectFire.wait()
//    }
//
//    @UiThreadTest
//    @Test
//    fun scheduleAndCancelPreviousSuspend_givenPreviouslyRunningTimer_expectCancelAndStartNew() = runBlocking {
//        val expectNoFire = AsyncWait(1, isInverted = true)
//        timer.scheduleAndCancelPreviousSuspend(Seconds(0.1)) {
//            expectNoFire.fulfill()
//        }
//
//        timer.cancel()
//
//        val expectFire = AsyncWait(1)
//        timer.scheduleAndCancelPreviousSuspend(Seconds(0.2)) { // be a slightly larger value then the previous timer to give it a chance to finish if its going to.
//            expectFire.fulfill()
//        }
//
//        expectFire.wait()
//    }
//
//    @UiThreadTest
//    @Test
//    fun scheduleAndCancelPrevious_givenCallMultipleTimes_expectScheduleNewTimerEachTime() {
//        AsyncWait(1).also { asyncWait ->
//            timer.scheduleAndCancelPrevious(Seconds(0.1)) {
//                asyncWait.fulfill()
//            }
//            asyncWait.wait()
//        }
//
//        AsyncWait(1).also { asyncWait ->
//            timer.scheduleAndCancelPrevious(Seconds(0.1)) {
//                asyncWait.fulfill()
//            }
//            asyncWait.wait()
//        }
//    }
//
//    @Test
//    fun scheduleAndCancelPreviousSuspend_givenCallMultipleTimes_expectScheduleNewTimerEachTime(): Unit = runBlocking {
//        AsyncWait(1).also { asyncWait ->
//            timer.scheduleAndCancelPreviousSuspend(Seconds(0.1)) {
//                asyncWait.fulfill()
//            }
//            asyncWait.wait()
//        }
//
//        AsyncWait(1).also { asyncWait ->
//            timer.scheduleAndCancelPreviousSuspend(Seconds(0.1)) {
//                asyncWait.fulfill()
//            }
//            asyncWait.wait()
//        }
//    }
//
//    @Test
//    fun cancel_givenNoScheduleScheduled_expectNoErrors() {
//        timer.cancel()
//    }
//
//    @UiThreadTest
//    @Test
//    fun cancel_givenScheduled_expectTimerCanceled() {
//        val expect = AsyncWait(1, isInverted = true)
//        val didSchedule = timer.scheduleIfNotAlready(Seconds(0.1)) {
//            expect.fulfill()
//        }
//        didSchedule.shouldBeTrue()
//
//        timer.cancel()
//
//        expect.wait()
//    }
// }

package io.customer.location

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationUserChangeHandlerTest {
    private val tracker: LocationTracker = mockk(relaxUnitFun = true)
    private val services: LocationServices = mockk(relaxUnitFun = true)

    init {
        every { tracker.launchRestore(any()) } answers { firstArg<CoroutineScope>().launch { } }
    }

    private fun config(mode: LocationTrackingMode) =
        LocationModuleConfig.Builder().setLocationTrackingMode(mode).build()

    @Test
    fun givenIdentifiedWhileRestoring_expectHandledAfterRestore() {
        every { tracker.trackedLocation } returns null
        val restoreStarted = CountDownLatch(1)
        val releaseRestore = CountDownLatch(1)
        val handled = CountDownLatch(1)
        every { tracker.launchRestore(any()) } answers {
            firstArg<CoroutineScope>().launch {
                restoreStarted.countDown()
                releaseRestore.await(5, TimeUnit.SECONDS)
            }
        }
        every { tracker.onUserIdentified() } answers { handled.countDown() }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val handler = LocationUserChangeHandler(scope, config(LocationTrackingMode.ON_APP_START), tracker, services)
            restoreStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            handler.onUserIdentified()

            handled.await(200, TimeUnit.MILLISECONDS).shouldBeFalse()

            releaseRestore.countDown()
            handled.await(5, TimeUnit.SECONDS).shouldBeTrue()
            verifyOrder {
                tracker.onUserIdentified()
                services.requestLocationUpdate()
            }
        } finally {
            releaseRestore.countDown()
            scope.cancel()
        }
    }

    @Test
    fun givenTwoIdentifiesBackToBack_expectHandledOneAtATime() {
        every { tracker.trackedLocation } returns null
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val bothDone = CountDownLatch(2)
        val handled = AtomicInteger()
        every { tracker.onUserIdentified() } answers {
            if (handled.incrementAndGet() == 1) {
                firstStarted.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            }
            bothDone.countDown()
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val handler = LocationUserChangeHandler(scope, config(LocationTrackingMode.ON_APP_START), tracker, services)
            handler.onUserIdentified()
            handler.onUserIdentified()

            firstStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            Thread.sleep(200)
            handled.get() shouldBeEqualTo 1

            releaseFirst.countDown()
            bothDone.await(5, TimeUnit.SECONDS).shouldBeTrue()
        } finally {
            releaseFirst.countDown()
            scope.cancel()
        }
    }

    @Test
    fun givenOnAppStartAndLocationKnown_expectNoRequest() {
        every { tracker.trackedLocation } returns LocationCoordinates(latitude = 37.7749, longitude = -122.4194)
        val scope = TestScope(UnconfinedTestDispatcher())

        LocationUserChangeHandler(scope, config(LocationTrackingMode.ON_APP_START), tracker, services).onUserIdentified()

        verify(exactly = 1) { tracker.onUserIdentified() }
        verify(exactly = 0) { services.requestLocationUpdate() }
    }

    @Test
    fun givenManualAndNoLocation_expectNoRequest() {
        every { tracker.trackedLocation } returns null
        val scope = TestScope(UnconfinedTestDispatcher())

        LocationUserChangeHandler(scope, config(LocationTrackingMode.MANUAL), tracker, services).onUserIdentified()

        verify(exactly = 1) { tracker.onUserIdentified() }
        verify(exactly = 0) { services.requestLocationUpdate() }
    }
}

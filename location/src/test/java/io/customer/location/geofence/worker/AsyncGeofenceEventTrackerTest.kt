package io.customer.location.geofence.worker

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.DispatchersProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AsyncGeofenceEventTrackerTest : RobolectricTest() {

    private val mockTracker: GeofenceEventTracker = mockk(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchersProvider: DispatchersProvider = object : DispatchersProvider {
        override val background: CoroutineDispatcher get() = testDispatcher
        override val main: CoroutineDispatcher get() = testDispatcher
        override val default: CoroutineDispatcher get() = testDispatcher
    }

    private lateinit var asyncTracker: AsyncGeofenceEventTracker

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { })
        asyncTracker = AsyncGeofenceEventTracker(mockTracker, dispatchersProvider)
    }

    @Test
    fun trackEvent_expectUnderlyingTrackerInvokedWithSameArgs() = runTest {
        coEvery {
            mockTracker.trackEvent(any(), any(), any(), any(), any())
        } returns Result.success(Unit)

        asyncTracker.trackEvent(
            geofenceId = "biz-1",
            transition = Event.GeofenceTransition.ENTER,
            latitude = 1.0,
            longitude = 2.0,
            timestamp = 42L
        )

        coVerify(exactly = 1) {
            mockTracker.trackEvent(
                geofenceId = "biz-1",
                transition = Event.GeofenceTransition.ENTER,
                latitude = 1.0,
                longitude = 2.0,
                timestamp = 42L
            )
        }
    }

    @Test
    fun trackEvent_givenNullLatLng_expectNullPassedThrough() = runTest {
        coEvery {
            mockTracker.trackEvent(any(), any(), any(), any(), any())
        } returns Result.success(Unit)

        asyncTracker.trackEvent(
            geofenceId = "biz-2",
            transition = Event.GeofenceTransition.EXIT,
            latitude = null,
            longitude = null,
            timestamp = 0L
        )

        coVerify(exactly = 1) {
            mockTracker.trackEvent(
                geofenceId = "biz-2",
                transition = Event.GeofenceTransition.EXIT,
                latitude = null,
                longitude = null,
                timestamp = 0L
            )
        }
    }
}

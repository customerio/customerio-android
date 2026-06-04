package io.customer.location.geofence.worker

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.location.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.data.store.PendingDeliveryStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
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
    private val mockStore: PendingDeliveryStore<PendingGeofenceDelivery> = mockk(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchersProvider: DispatchersProvider = object : DispatchersProvider {
        override val background: CoroutineDispatcher get() = testDispatcher
        override val main: CoroutineDispatcher get() = testDispatcher
        override val default: CoroutineDispatcher get() = testDispatcher
    }

    private lateinit var asyncTracker: AsyncGeofenceEventTracker

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { })
        asyncTracker = AsyncGeofenceEventTracker(mockTracker, mockStore, dispatchersProvider)
    }

    @Test
    fun trackEvent_givenClaimWonAndSuccess_expectTrackerCalledWithEntryFields() = runTest {
        every { mockStore.claim(any()) } returns true
        coEvery { mockTracker.trackEvent(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        val entry = PendingGeofenceDelivery("biz-1", Event.GeofenceTransition.ENTER, 1.0, 2.0, 42L)

        asyncTracker.trackEvent(entry)

        // Claim before send (shared exactly-once contract), then deliver.
        verify(exactly = 1) { mockStore.claim("biz-1_ENTER_42") }
        coVerify(exactly = 1) {
            mockTracker.trackEvent(
                geofenceId = "biz-1",
                transition = Event.GeofenceTransition.ENTER,
                latitude = 1.0,
                longitude = 2.0,
                timestamp = 42L
            )
        }
        // claim() already removed it; success must not re-append.
        verify(exactly = 0) { mockStore.append(any()) }
    }

    @Test
    fun trackEvent_givenNullLatLng_expectNullPassedThrough() = runTest {
        every { mockStore.claim(any()) } returns true
        coEvery { mockTracker.trackEvent(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        val entry = PendingGeofenceDelivery("biz-2", Event.GeofenceTransition.EXIT, null, null, 0L)

        asyncTracker.trackEvent(entry)

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

    @Test
    fun trackEvent_givenClaimLost_expectNoSend() = runTest {
        // Foreground flush already claimed + delivered this entry: do not double-send.
        every { mockStore.claim(any()) } returns false
        val entry = PendingGeofenceDelivery("biz-3", Event.GeofenceTransition.ENTER, 1.0, 2.0, 7L)

        asyncTracker.trackEvent(entry)

        coVerify(exactly = 0) { mockTracker.trackEvent(any(), any(), any(), any(), any()) }
    }

    @Test
    fun trackEvent_givenFailure_expectEntryRestoredForFlush() = runTest {
        every { mockStore.claim(any()) } returns true
        coEvery { mockTracker.trackEvent(any(), any(), any(), any(), any()) } returns
            Result.failure(IOException("network down"))
        val entry = PendingGeofenceDelivery("biz-4", Event.GeofenceTransition.ENTER, 1.0, 2.0, 9L)

        asyncTracker.trackEvent(entry)

        // Restored so the foreground flush can still deliver it.
        verify(exactly = 1) { mockStore.append(entry) }
    }
}

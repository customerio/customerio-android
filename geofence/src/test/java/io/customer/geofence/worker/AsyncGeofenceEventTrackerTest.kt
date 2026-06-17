package io.customer.geofence.worker

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.store.PendingGeofenceDelivery
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
    private val mockLogger: GeofenceLogger = mockk(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchersProvider: DispatchersProvider = object : DispatchersProvider {
        override val background: CoroutineDispatcher get() = testDispatcher
        override val main: CoroutineDispatcher get() = testDispatcher
        override val default: CoroutineDispatcher get() = testDispatcher
    }

    private lateinit var asyncTracker: AsyncGeofenceEventTracker

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { })
        asyncTracker = AsyncGeofenceEventTracker(mockTracker, mockStore, dispatchersProvider, mockLogger)
    }

    @Test
    fun trackEvent_givenClaimWonAndSuccess_expectTrackerCalledWithEntryAndUserId() = runTest {
        every { mockStore.claim(any()) } returns true
        coEvery { mockTracker.trackEvent(any()) } returns Result.success(Unit)
        val entry = PendingGeofenceDelivery("biz-1", Event.GeofenceTransition.ENTER, 42L, "user-42")

        asyncTracker.trackEvent(entry)

        // Claim before send (shared exactly-once contract), then deliver with the snapshotted entry.
        verify(exactly = 1) { mockStore.claim("biz-1_ENTER_42") }
        coVerify(exactly = 1) { mockTracker.trackEvent(entry) }
        // claim() already removed it; success must not re-append.
        verify(exactly = 0) { mockStore.append(any()) }
    }

    @Test
    fun trackEvent_givenClaimLost_expectNoSend() = runTest {
        // Foreground flush already claimed + delivered this entry: do not double-send.
        every { mockStore.claim(any()) } returns false
        val entry = PendingGeofenceDelivery("biz-3", Event.GeofenceTransition.ENTER, 7L, "user-42")

        asyncTracker.trackEvent(entry)

        coVerify(exactly = 0) { mockTracker.trackEvent(any()) }
    }

    @Test
    fun trackEvent_givenFailure_expectEntryRestoredForFlush() = runTest {
        every { mockStore.claim(any()) } returns true
        coEvery { mockTracker.trackEvent(any()) } returns
            Result.failure(IOException("network down"))
        val entry = PendingGeofenceDelivery("biz-4", Event.GeofenceTransition.ENTER, 9L, "user-42")

        asyncTracker.trackEvent(entry)

        // Restored so the foreground flush can still deliver it.
        verify(exactly = 1) { mockStore.append(entry) }
    }

    @Test
    fun trackEvent_givenNullUserId_expectNoClaimAndNoSend() = runTest {
        // Anonymous session: HTTP needs a userId, so we leave the entry in
        // the store for the foreground flush to publish via anonymousId.
        val entry = PendingGeofenceDelivery("biz-anon", Event.GeofenceTransition.ENTER, 11L, userId = null)

        asyncTracker.trackEvent(entry)

        verify(exactly = 0) { mockStore.claim(any()) }
        coVerify(exactly = 0) { mockTracker.trackEvent(any()) }
        verify { mockLogger.logEventDeliveryDeferredAnonymous("biz-anon", "ENTER") }
    }
}

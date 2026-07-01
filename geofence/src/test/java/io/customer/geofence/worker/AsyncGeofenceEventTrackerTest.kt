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
    fun trackEvent_givenPendingEntryAndSuccess_expectSentThenRemoved() = runTest {
        val entry = PendingGeofenceDelivery("biz-1", Event.GeofenceTransition.ENTER, 42L, "user-42", transitionId = "tid-1")
        every { mockStore.loadAll() } returns listOf(entry)
        coEvery { mockTracker.trackEvent(any()) } returns Result.success(Unit)

        asyncTracker.trackEvent(entry)

        // At-least-once contract: send with the snapshotted entry, remove only after success.
        coVerify(exactly = 1) { mockTracker.trackEvent(entry) }
        verify(exactly = 1) { mockStore.remove("biz-1_ENTER_42") }
    }

    @Test
    fun trackEvent_givenEntryAlreadyDelivered_expectNoSend() = runTest {
        // Foreground flush already delivered + removed this entry: do not re-send.
        every { mockStore.loadAll() } returns emptyList()
        val entry = PendingGeofenceDelivery("biz-3", Event.GeofenceTransition.ENTER, 7L, "user-42", transitionId = "tid-3")

        asyncTracker.trackEvent(entry)

        coVerify(exactly = 0) { mockTracker.trackEvent(any()) }
        verify(exactly = 0) { mockStore.remove(any()) }
    }

    @Test
    fun trackEvent_givenFailure_expectEntryKeptForFlush() = runTest {
        val entry = PendingGeofenceDelivery("biz-4", Event.GeofenceTransition.ENTER, 9L, "user-42", transitionId = "tid-4")
        every { mockStore.loadAll() } returns listOf(entry)
        coEvery { mockTracker.trackEvent(any()) } returns
            Result.failure(IOException("network down"))

        asyncTracker.trackEvent(entry)

        // Never removed, so the foreground flush can still deliver it.
        verify(exactly = 0) { mockStore.remove(any()) }
    }

    @Test
    fun trackEvent_givenNullUserId_expectNoLoadAndNoSend() = runTest {
        // Anonymous session: HTTP needs a userId, so we leave the entry in
        // the store for the foreground flush to publish via anonymousId.
        val entry = PendingGeofenceDelivery("biz-anon", Event.GeofenceTransition.ENTER, 11L, userId = null, transitionId = "tid-anon")

        asyncTracker.trackEvent(entry)

        verify(exactly = 0) { mockStore.loadAll() }
        coVerify(exactly = 0) { mockTracker.trackEvent(any()) }
        verify { mockLogger.logEventDeliveryDeferredAnonymous("biz-anon", "ENTER") }
    }
}

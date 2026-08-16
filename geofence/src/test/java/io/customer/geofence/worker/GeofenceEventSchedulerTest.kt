package io.customer.geofence.worker

import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import com.google.common.util.concurrent.Futures
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceEventSchedulerTest : RobolectricTest() {

    private val workManagerProvider: CustomerIOWorkManagerProvider = mockk(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)
    private val asyncTracker: AsyncGeofenceEventTracker = mockk(relaxed = true)

    private lateinit var scheduler: GeofenceEventScheduler

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { })
        // Default: enqueue returns an immediately-successful Operation so suspend await() resolves.
        every {
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        } returns immediateSuccessfulOperation()
        scheduler = GeofenceEventScheduler(workManagerProvider, asyncTracker)
    }

    private fun immediateSuccessfulOperation(): Operation = mockk(relaxed = true) {
        every { result } returns Futures.immediateFuture(Operation.SUCCESS)
    }

    @Test
    fun schedule_givenWorkManagerAvailable_expectUniqueWorkEnqueued() = runTest {
        every { workManagerProvider.getWorkManager() } returns workManager
        val workRequestSlot = slot<OneTimeWorkRequest>()
        val uniqueKeySlot = slot<String>()

        scheduler.schedule(
            PendingGeofenceDelivery(
                geofenceId = "biz-geofence-1",
                transition = Event.GeofenceTransition.ENTER,
                timestamp = 1_234L,
                userId = "user-42",
                transitionId = "tid-sched",
                geofenceName = "Coffee Shop"
            )
        )

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                capture(uniqueKeySlot),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                capture(workRequestSlot)
            )
        }
        uniqueKeySlot.captured shouldBeEqualTo "cio-geofence-delivery-queue"

        // inputData carries only the store key; the worker loads the full row from the pending store.
        val input = workRequestSlot.captured.workSpec.input
        input.getString("entry_key") shouldBeEqualTo "biz-geofence-1_ENTER_tid-sched_none"
        input.hasKeyWithValueOfType("geofence_id", String::class.java) shouldBeEqualTo false
        input.hasKeyWithValueOfType("metadata", String::class.java) shouldBeEqualTo false

        val constraints = workRequestSlot.captured.workSpec.constraints
        constraints shouldNotBe null
        constraints.requiredNetworkType shouldBeEqualTo NetworkType.CONNECTED
    }

    @Test
    fun schedule_givenGeoset_expectGeosetIdInKey() = runTest {
        every { workManagerProvider.getWorkManager() } returns workManager
        val workRequestSlot = slot<OneTimeWorkRequest>()
        val uniqueKeySlot = slot<String>()

        scheduler.schedule(
            PendingGeofenceDelivery(
                geofenceId = "biz-geofence-1",
                transition = Event.GeofenceTransition.ENTER,
                timestamp = 1_234L,
                userId = "user-42",
                transitionId = "tid-sched",
                geosetId = "9"
            )
        )

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                capture(uniqueKeySlot),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                capture(workRequestSlot)
            )
        }
        // All transitions share one continuation chain, while the row key remains the worker input.
        uniqueKeySlot.captured shouldBeEqualTo "cio-geofence-delivery-queue"
        workRequestSlot.captured.workSpec.input.getString("entry_key") shouldBeEqualTo
            "biz-geofence-1_ENTER_tid-sched_9"
    }

    @Test
    fun schedule_givenWorkManagerUnavailable_expectFallbackToAsyncTracker() = runTest {
        every { workManagerProvider.getWorkManager() } returns null
        val entry = PendingGeofenceDelivery(
            geofenceId = "biz-geofence-3",
            transition = Event.GeofenceTransition.ENTER,
            timestamp = 42L,
            userId = "user-42",
            transitionId = "tid-fallback"
        )

        scheduler.schedule(entry)

        verify(exactly = 0) { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
        verify(exactly = 1) { asyncTracker.trackEvent(entry) }
    }
}

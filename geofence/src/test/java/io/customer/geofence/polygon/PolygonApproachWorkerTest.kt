package io.customer.geofence.polygon

import android.location.Location
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.Futures
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.GeofenceRegionStoreImpl
import io.customer.geofence.store.PendingPolygonApproachBatch
import io.customer.geofence.store.PendingPolygonApproachLocation
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PolygonApproachWorkerTest : RobolectricTest() {
    private val workManagerProvider: CustomerIOWorkManagerProvider = mockk(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)
    private val bootSessionProvider = PolygonBootSessionProvider { CURRENT_BOOT }
    private val sdkStore: GeofenceRegionStore = mockk(relaxed = true)
    private lateinit var store: GeofenceRegionStore

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    android {
                        overrideDependency<PolygonBootSessionProvider>(bootSessionProvider)
                        overrideDependency<GeofenceRegionStore>(sdkStore)
                    }
                }
            }
        )
        store = GeofenceRegionStoreImpl(
            applicationMock,
            GeofenceJsonSerializer(),
            mockk(relaxed = true),
            locationCrypto = object : io.customer.geofence.store.GeofenceLocationCrypto {
                override fun encrypt(plaintext: String): String = "encrypted:${plaintext.reversed()}"
                override fun decrypt(encoded: String): String =
                    encoded.removePrefix("encrypted:").reversed()
            }
        ).also {
            it.clearAll()
            it.beginUserSession("user-1")
        }
        every { workManagerProvider.getWorkManager() } returns workManager
        every {
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        } returns immediateSuccessfulOperation()
    }

    @Test
    fun scheduler_givenLargeBatch_expectEncryptedStoreChunksAndControlOnlyWork() = runTest {
        val requests = mutableListOf<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(
                any(),
                any(),
                capture(requests)
            )
        } returns immediateSuccessfulOperation()
        val scheduler = PolygonApproachWorkScheduler(
            workManagerProvider,
            store,
            bootSessionProvider
        )
        val locations = List(33) { index -> location(index) }
        val generation = store.userStateGeneration()

        scheduler.enqueue(locations, generation) shouldBeEqualTo true

        val pending = store.getPendingPolygonApproachBatches()
        pending.size shouldBeEqualTo 2
        pending.map { it.userStateGeneration } shouldBeEqualTo listOf(generation, generation)
        pending.map { it.bootSessionId } shouldBeEqualTo listOf(CURRENT_BOOT, CURRENT_BOOT)
        pending.flatMap { it.locations }.map { it.latitude } shouldBeEqualTo
            locations.map(Location::getLatitude)
        requests.size shouldBeEqualTo 2
        requests.forEach { it.workSpec.input.keyValueMap.size shouldBeEqualTo 0 }
        verify(exactly = 2) {
            workManager.enqueueUniqueWork(
                PolygonApproachWorkScheduler.ORDERED_POLYGON_APPROACH_QUEUE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any<OneTimeWorkRequest>()
            )
        }
    }

    @Test
    fun worker_givenBatchFromPreviousBoot_expectDropsWithoutEvaluation() = runTest {
        var pending = listOf(
            PendingPolygonApproachBatch(
                id = "old-boot-batch",
                userStateGeneration = 1L,
                bootSessionId = "previous-boot",
                locations = listOf(
                    PendingPolygonApproachLocation(
                        latitude = 37.0,
                        longitude = -122.0,
                        accuracy = 5f,
                        speed = null,
                        timestampMillis = 1_000L,
                        elapsedRealtimeNanos = 40_000_000_000L
                    )
                )
            )
        )
        every { sdkStore.getPendingPolygonApproachBatches() } answers { pending }
        every { sdkStore.removePendingPolygonApproachBatch(any()) } answers {
            pending = pending.filterNot { it.id == firstArg<String>() }
            true
        }

        val result = TestListenableWorkerBuilder<PolygonApproachWorker>(applicationMock)
            .build()
            .doWork()

        result shouldBeEqualTo ListenableWorker.Result.success()
        pending shouldBeEqualTo emptyList()
    }

    private fun location(index: Int) = Location("test").apply {
        latitude = 37.0 + index / 1_000.0
        longitude = -122.0 - index / 1_000.0
        accuracy = 5f + index
        speed = 2f + index
        time = 1_000L + index
        elapsedRealtimeNanos = 10_000L + index
    }

    private fun immediateSuccessfulOperation(): Operation = mockk(relaxed = true) {
        every { result } returns Futures.immediateFuture(Operation.SUCCESS)
    }

    private companion object {
        const val CURRENT_BOOT = "boot-42"
    }
}

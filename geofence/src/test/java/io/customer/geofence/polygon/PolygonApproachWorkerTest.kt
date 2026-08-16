package io.customer.geofence.polygon

import android.location.Location
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import com.google.common.util.concurrent.Futures
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
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

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { })
        every { workManagerProvider.getWorkManager() } returns workManager
        every {
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        } returns immediateSuccessfulOperation()
    }

    @Test
    fun codec_givenLargeBatch_expectChunkedLosslessRoundTrip() {
        val locations = List(33) { index ->
            Location("test").apply {
                latitude = 37.0 + index / 1_000.0
                longitude = -122.0 - index / 1_000.0
                accuracy = 5f + index
                speed = 2f + index
                time = 1_000L + index
                elapsedRealtimeNanos = 10_000L + index
            }
        }

        val encoded = PolygonApproachWorkCodec.encode(locations, 7L)
        val decoded = encoded.mapNotNull(PolygonApproachWorkCodec::decode)

        encoded.size shouldBeEqualTo 2
        decoded.map { it.userStateGeneration } shouldBeEqualTo listOf(7L, 7L)
        val restored = decoded.flatMap { it.locations }
        restored.size shouldBeEqualTo locations.size
        restored.forEachIndexed { index, location ->
            location.latitude shouldBeEqualTo locations[index].latitude
            location.longitude shouldBeEqualTo locations[index].longitude
            location.accuracy shouldBeEqualTo locations[index].accuracy
            location.speed shouldBeEqualTo locations[index].speed
            location.time shouldBeEqualTo locations[index].time
            location.elapsedRealtimeNanos shouldBeEqualTo locations[index].elapsedRealtimeNanos
        }
    }

    @Test
    fun scheduler_givenMultipleChunks_expectOneOrderedWorkChain() = runTest {
        val scheduler = PolygonApproachWorkScheduler(workManagerProvider)
        val locations = List(33) { index ->
            Location("test").apply {
                latitude = 37.0
                longitude = -122.0
                accuracy = 5f
                time = 1_000L + index
                elapsedRealtimeNanos = 10_000L + index
            }
        }

        scheduler.enqueue(locations, 9L) shouldBeEqualTo true

        verify(exactly = 2) {
            workManager.enqueueUniqueWork(
                PolygonApproachWorkScheduler.ORDERED_POLYGON_APPROACH_QUEUE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any<OneTimeWorkRequest>()
            )
        }
    }

    private fun immediateSuccessfulOperation(): Operation = mockk(relaxed = true) {
        every { result } returns Futures.immediateFuture(Operation.SUCCESS)
    }
}

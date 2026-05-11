package io.customer.location.geofence.worker

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.communication.Event
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceEventWorkerTest : RobolectricTest() {

    private val tracker: GeofenceEventTracker = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    android { overrideDependency<GeofenceEventTracker>(tracker) }
                }
            }
        )
    }

    @Test
    fun doWork_givenValidEnterInput_expectSuccessAndTrackerCalled() = runTest {
        val inputData = buildInputData(
            geofenceId = "biz-1",
            transition = "ENTER",
            latitude = 1.0,
            longitude = 2.0,
            timestamp = 99L
        )
        coEvery { tracker.trackEvent(any(), any(), any(), any(), any()) } returns Result.success(Unit)

        val result = createWorker(inputData).doWork()

        result shouldBeEqualTo ListenableWorker.Result.success()
        coVerify(exactly = 1) {
            tracker.trackEvent(
                geofenceId = "biz-1",
                transition = Event.GeofenceTransition.ENTER,
                latitude = 1.0,
                longitude = 2.0,
                timestamp = 99L
            )
        }
    }

    @Test
    fun doWork_givenValidExitInput_expectExitTransitionPassed() = runTest {
        val inputData = buildInputData(geofenceId = "biz-2", transition = "EXIT", timestamp = 0L)
        coEvery { tracker.trackEvent(any(), any(), any(), any(), any()) } returns Result.success(Unit)

        createWorker(inputData).doWork()

        coVerify(exactly = 1) {
            tracker.trackEvent(
                geofenceId = "biz-2",
                transition = Event.GeofenceTransition.EXIT,
                latitude = any(),
                longitude = any(),
                timestamp = 0L
            )
        }
    }

    @Test
    fun doWork_givenMissingLatLng_expectNullPassedToTracker() = runTest {
        val inputData = Data.Builder()
            .putString("geofence_id", "biz-3")
            .putString("transition", "ENTER")
            .putLong("timestamp", 0L)
            .build()
        coEvery { tracker.trackEvent(any(), any(), any(), any(), any()) } returns Result.success(Unit)

        createWorker(inputData).doWork()

        coVerify(exactly = 1) {
            tracker.trackEvent(
                geofenceId = "biz-3",
                transition = Event.GeofenceTransition.ENTER,
                latitude = null,
                longitude = null,
                timestamp = 0L
            )
        }
    }

    @Test
    fun doWork_givenMissingGeofenceId_expectFailureWithoutTracking() = runTest {
        val inputData = buildInputData(geofenceId = null, transition = "ENTER")

        val result = createWorker(inputData).doWork()

        result shouldBeEqualTo ListenableWorker.Result.failure()
        coVerify(exactly = 0) { tracker.trackEvent(any(), any(), any(), any(), any()) }
    }

    @Test
    fun doWork_givenMissingTransition_expectFailureWithoutTracking() = runTest {
        val inputData = buildInputData(geofenceId = "biz", transition = null)

        val result = createWorker(inputData).doWork()

        result shouldBeEqualTo ListenableWorker.Result.failure()
        coVerify(exactly = 0) { tracker.trackEvent(any(), any(), any(), any(), any()) }
    }

    @Test
    fun doWork_givenInvalidTransition_expectFailureWithoutTracking() = runTest {
        val inputData = buildInputData(geofenceId = "biz", transition = "DWELL")

        val result = createWorker(inputData).doWork()

        result shouldBeEqualTo ListenableWorker.Result.failure()
        coVerify(exactly = 0) { tracker.trackEvent(any(), any(), any(), any(), any()) }
    }

    @Test
    fun doWork_givenIOException_expectRetry() = runTest {
        val inputData = buildInputData(geofenceId = "biz", transition = "ENTER")
        coEvery { tracker.trackEvent(any(), any(), any(), any(), any()) } returns
            Result.failure(IOException("network down"))

        val result = createWorker(inputData).doWork()

        result shouldBeEqualTo ListenableWorker.Result.retry()
    }

    @Test
    fun doWork_givenNonIOException_expectFailure() = runTest {
        val inputData = buildInputData(geofenceId = "biz", transition = "ENTER")
        coEvery { tracker.trackEvent(any(), any(), any(), any(), any()) } returns
            Result.failure(IllegalStateException("bad state"))

        val result = createWorker(inputData).doWork()

        result shouldBeEqualTo ListenableWorker.Result.failure()
    }

    private fun buildInputData(
        geofenceId: String?,
        transition: String?,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        timestamp: Long = 0L
    ): Data {
        val builder = Data.Builder()
            .putDouble("latitude", latitude)
            .putDouble("longitude", longitude)
            .putLong("timestamp", timestamp)
        geofenceId?.let { builder.putString("geofence_id", it) }
        transition?.let { builder.putString("transition", it) }
        return builder.build()
    }

    private fun createWorker(inputData: Data): GeofenceEventWorker {
        return TestListenableWorkerBuilder<GeofenceEventWorker>(applicationMock)
            .setInputData(inputData)
            .build()
    }
}

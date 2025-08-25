package io.customer.messagingpush.processor

import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.random
import io.customer.messagingpush.PushDeliveryTracker
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.sdk.events.Metric
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushDeliveryMetricsWorkerTest : IntegrationTest() {

    private val mockPushDeliveryTracker = mockk<PushDeliveryTracker>(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<PushDeliveryTracker>(mockPushDeliveryTracker)
                    }
                }
            }
        )
    }

    @Test
    fun doWork_givenValidInputData_expectSuccessfulTracking() = runTest {
        val deliveryId = String.random
        val deliveryToken = String.random
        val inputData = createInputData(deliveryId, deliveryToken)
        
        coEvery { 
            mockPushDeliveryTracker.trackMetric(
                event = Metric.Delivered.name,
                deliveryId = deliveryId,
                token = deliveryToken
            )
        } returns Result.success(Unit)

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.success()
        
        coVerify(exactly = 1) {
            mockPushDeliveryTracker.trackMetric(
                event = Metric.Delivered.name,
                deliveryId = deliveryId,
                token = deliveryToken
            )
        }
    }

    @Test
    fun doWork_givenTrackingFails_expectWorkerFailure() = runTest {
        val deliveryId = String.random
        val deliveryToken = String.random
        val inputData = createInputData(deliveryId, deliveryToken)
        
        coEvery { 
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        } returns Result.failure(Exception("Network error"))

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.failure()
        
        coVerify(exactly = 1) {
            mockPushDeliveryTracker.trackMetric(
                event = Metric.Delivered.name,
                deliveryId = deliveryId,
                token = deliveryToken
            )
        }
    }

    @Test
    fun doWork_givenNullDeliveryId_expectSuccessWithoutTracking() = runTest {
        val inputData = createInputData(deliveryId = null, deliveryToken = String.random)

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.success()
        
        coVerify(exactly = 0) {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        }
    }

    @Test
    fun doWork_givenEmptyDeliveryId_expectSuccessWithoutTracking() = runTest {
        val inputData = createInputData(deliveryId = "", deliveryToken = String.random)

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.success()
        
        coVerify(exactly = 0) {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        }
    }

    @Test
    fun doWork_givenNullDeliveryToken_expectSuccessWithoutTracking() = runTest {
        val inputData = createInputData(deliveryId = String.random, deliveryToken = null)

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.success()
        
        coVerify(exactly = 0) {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        }
    }

    @Test
    fun doWork_givenEmptyDeliveryToken_expectSuccessWithoutTracking() = runTest {
        val inputData = createInputData(deliveryId = String.random, deliveryToken = "")

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.success()
        
        coVerify(exactly = 0) {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        }
    }

    @Test
    fun doWork_givenBothParametersEmpty_expectSuccessWithoutTracking() = runTest {
        val inputData = createInputData(deliveryId = "", deliveryToken = "")

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.success()
        
        coVerify(exactly = 0) {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        }
    }

    @Test
    fun doWork_givenBothParametersNull_expectSuccessWithoutTracking() = runTest {
        val inputData = createInputData(deliveryId = null, deliveryToken = null)

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.success()
        
        coVerify(exactly = 0) {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        }
    }

    @Test
    fun doWork_givenNoInputData_expectSuccessWithoutTracking() = runTest {
        val inputData = Data.EMPTY

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.success()
        
        coVerify(exactly = 0) {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        }
    }

    private fun createInputData(deliveryId: String?, deliveryToken: String?): Data {
        val builder = Data.Builder()
        deliveryId?.let { builder.putString("delivery-id", it) }
        deliveryToken?.let { builder.putString("delivery-token", it) }
        return builder.build()
    }

    private fun createWorker(inputData: Data): PushDeliveryMetricsWorker {
        return TestListenableWorkerBuilder<PushDeliveryMetricsWorker>(applicationMock)
            .setInputData(inputData)
            .build()
    }
}

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
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
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
                token = deliveryToken,
                event = Metric.Delivered.name,
                deliveryId = deliveryId
            )
        } returns Result.success(Unit)

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.success()

        coVerify(exactly = 1) {
            mockPushDeliveryTracker.trackMetric(
                token = deliveryToken,
                event = Metric.Delivered.name,
                deliveryId = deliveryId
            )
        }
    }

    @Test
    fun doWork_givenTrackingFailsWithGenericException_expectWorkerFailure() = runTest {
        val deliveryId = String.random
        val deliveryToken = String.random
        val inputData = createInputData(deliveryId, deliveryToken)

        coEvery {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        } returns Result.failure(Exception("Generic network error"))

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.failure()

        coVerify(exactly = 1) {
            mockPushDeliveryTracker.trackMetric(
                token = deliveryToken,
                event = Metric.Delivered.name,
                deliveryId = deliveryId
            )
        }
    }

    @Test
    fun doWork_givenNullDeliveryId_expectFailureWithoutTracking() = runTest {
        val inputData = createInputData(deliveryId = null, deliveryToken = String.random)

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.failure()

        coVerify(exactly = 0) {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        }
    }

    @Test
    fun doWork_givenEmptyDeliveryId_expectFailureWithoutTracking() = runTest {
        val inputData = createInputData(deliveryId = "", deliveryToken = String.random)

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.failure()

        coVerify(exactly = 0) {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        }
    }

    @Test
    fun doWork_givenNullDeliveryToken_expectFailureWithoutTracking() = runTest {
        val inputData = createInputData(deliveryId = String.random, deliveryToken = null)

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.failure()

        coVerify(exactly = 0) {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        }
    }

    @Test
    fun doWork_givenEmptyDeliveryToken_expectFailureWithoutTracking() = runTest {
        val inputData = createInputData(deliveryId = String.random, deliveryToken = "")

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.failure()

        coVerify(exactly = 0) {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        }
    }

    @Test
    fun doWork_givenBothParametersEmpty_expectFailureWithoutTracking() = runTest {
        val inputData = createInputData(deliveryId = "", deliveryToken = "")

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.failure()

        coVerify(exactly = 0) {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        }
    }

    @Test
    fun doWork_givenBothParametersNull_expectFailureWithoutTracking() = runTest {
        val inputData = createInputData(deliveryId = null, deliveryToken = null)

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.failure()

        coVerify(exactly = 0) {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        }
    }

    @Test
    fun doWork_givenNoInputData_expectFailureWithoutTracking() = runTest {
        val inputData = Data.EMPTY

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.failure()

        coVerify(exactly = 0) {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        }
    }

    @Test
    fun doWork_givenTrackingFailsWithIOException_expectWorkerRetry() = runTest {
        val deliveryId = String.random
        val deliveryToken = String.random
        val inputData = createInputData(deliveryId, deliveryToken)

        coEvery {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        } returns Result.failure(IOException("Network timeout"))

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.retry()

        coVerify(exactly = 1) {
            mockPushDeliveryTracker.trackMetric(
                token = deliveryToken,
                event = Metric.Delivered.name,
                deliveryId = deliveryId
            )
        }
    }

    @Test
    fun doWork_givenTrackingFailsWithNonIOException_expectWorkerFailure() = runTest {
        val deliveryId = String.random
        val deliveryToken = String.random
        val inputData = createInputData(deliveryId, deliveryToken)

        coEvery {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        } returns Result.failure(IllegalArgumentException("Invalid argument"))

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.failure()

        coVerify(exactly = 1) {
            mockPushDeliveryTracker.trackMetric(
                token = deliveryToken,
                event = Metric.Delivered.name,
                deliveryId = deliveryId
            )
        }
    }

    @Test
    fun doWork_givenTrackingFailsWithSocketTimeoutException_expectWorkerRetry() = runTest {
        val deliveryId = String.random
        val deliveryToken = String.random
        val inputData = createInputData(deliveryId, deliveryToken)

        coEvery {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        } returns Result.failure(SocketTimeoutException("Connection timeout"))

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.retry()

        coVerify(exactly = 1) {
            mockPushDeliveryTracker.trackMetric(
                token = deliveryToken,
                event = Metric.Delivered.name,
                deliveryId = deliveryId
            )
        }
    }

    @Test
    fun doWork_givenTrackingFailsWithUnknownHostException_expectWorkerRetry() = runTest {
        val deliveryId = String.random
        val deliveryToken = String.random
        val inputData = createInputData(deliveryId, deliveryToken)

        coEvery {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        } returns Result.failure(UnknownHostException("Host not found"))

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.retry()

        coVerify(exactly = 1) {
            mockPushDeliveryTracker.trackMetric(
                token = deliveryToken,
                event = Metric.Delivered.name,
                deliveryId = deliveryId
            )
        }
    }

    @Test
    fun doWork_givenTrackingFailsWithCertificateException_expectWorkerFailure() = runTest {
        val deliveryId = String.random
        val deliveryToken = String.random
        val inputData = createInputData(deliveryId, deliveryToken)

        coEvery {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        } returns Result.failure(CertificateException("Certificate error"))

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.failure()

        coVerify(exactly = 1) {
            mockPushDeliveryTracker.trackMetric(
                token = deliveryToken,
                event = Metric.Delivered.name,
                deliveryId = deliveryId
            )
        }
    }

    @Test
    fun doWork_givenVeryLongDeliveryId_expectSuccessfulTracking() = runTest {
        val deliveryId = "a".repeat(1000) // Very long delivery ID
        val deliveryToken = String.random
        val inputData = createInputData(deliveryId, deliveryToken)

        coEvery {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        } returns Result.success(Unit)

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.success()

        coVerify(exactly = 1) {
            mockPushDeliveryTracker.trackMetric(
                token = deliveryToken,
                event = Metric.Delivered.name,
                deliveryId = deliveryId
            )
        }
    }

    @Test
    fun doWork_givenVeryLongDeliveryToken_expectSuccessfulTracking() = runTest {
        val deliveryId = String.random
        val deliveryToken = "b".repeat(1000) // Very long delivery token
        val inputData = createInputData(deliveryId, deliveryToken)

        coEvery {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        } returns Result.success(Unit)

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.success()

        coVerify(exactly = 1) {
            mockPushDeliveryTracker.trackMetric(
                token = deliveryToken,
                event = Metric.Delivered.name,
                deliveryId = deliveryId
            )
        }
    }

    @Test
    fun doWork_givenSpecialCharactersInDeliveryId_expectSuccessfulTracking() = runTest {
        val deliveryId = "test-delivery-id-with-special-chars-!@#$%^&*()_+-={}|[]\\:\";'<>?,./"
        val deliveryToken = String.random
        val inputData = createInputData(deliveryId, deliveryToken)

        coEvery {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        } returns Result.success(Unit)

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.success()

        coVerify(exactly = 1) {
            mockPushDeliveryTracker.trackMetric(
                token = deliveryToken,
                event = Metric.Delivered.name,
                deliveryId = deliveryId
            )
        }
    }

    @Test
    fun doWork_givenUnicodeCharactersInDeliveryToken_expectSuccessfulTracking() = runTest {
        val deliveryId = String.random
        val deliveryToken = "test-token-with-unicode-\uD83D\uDE00\uD83D\uDE01\uD83E\uDD14\u4F60\u597D\u4E16\u754C" // Emojis and Chinese characters
        val inputData = createInputData(deliveryId, deliveryToken)

        coEvery {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        } returns Result.success(Unit)

        val worker = createWorker(inputData)
        val result = worker.doWork()

        result shouldBeEqualTo androidx.work.ListenableWorker.Result.success()

        coVerify(exactly = 1) {
            mockPushDeliveryTracker.trackMetric(
                token = deliveryToken,
                event = Metric.Delivered.name,
                deliveryId = deliveryId
            )
        }
    }

    @Test
    fun doWork_givenMetricConstantValue_expectCorrectMetricPassed() = runTest {
        val deliveryId = String.random
        val deliveryToken = String.random
        val inputData = createInputData(deliveryId, deliveryToken)

        coEvery {
            mockPushDeliveryTracker.trackMetric(any(), any(), any())
        } returns Result.success(Unit)

        val worker = createWorker(inputData)
        worker.doWork()

        // Verify that the exact metric constant is used
        coVerify(exactly = 1) {
            mockPushDeliveryTracker.trackMetric(
                token = deliveryToken,
                event = "Delivered", // Metric.Delivered.name is "Delivered"
                deliveryId = deliveryId
            )
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

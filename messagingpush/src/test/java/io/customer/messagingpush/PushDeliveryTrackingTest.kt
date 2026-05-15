package io.customer.messagingpush

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.random
import io.customer.commontest.util.DispatchersProviderStub
import io.customer.messagingpush.store.PendingPushDeliveryStore
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.sdk.core.network.CustomerIOHttpClient
import io.customer.sdk.core.network.HttpRequestParams
import io.customer.sdk.core.util.DispatchersProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushDeliveryTrackingTest : IntegrationTest() {

    private val httpClient: CustomerIOHttpClient = mockk(relaxed = true)
    private val pushDeliveryTracker = PushDeliveryTrackerImpl()
    private val mockPendingStore: PendingPushDeliveryStore = mockk(relaxed = true)
    private val mockDeliveryTracker: PushDeliveryTracker = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        // Override the httpClient in your DI so the SUT uses this mock.
                        overrideDependency<CustomerIOHttpClient>(httpClient)
                        overrideDependency<DispatchersProvider>(DispatchersProviderStub())
                    }
                }
            }
        )
    }

    @Test
    fun trackMetric_givenValidInputs_expectCorrectPathAndSuccessCallback() = runTest {
        val token = "token123"
        val event = "OPENED"
        val deliveryId = "delivery_abc"

        val capturedParams = slot<HttpRequestParams>()

        coEvery {
            httpClient.request(capture(capturedParams))
        } returns Result.success("Success")

        val result = pushDeliveryTracker.trackMetric(token, event, deliveryId)

        // Assert #1: Confirm the correct path.
        capturedParams.captured.path shouldBeEqualTo "/track"

        // Assert #2: The body should not be null.
        val requestBody = capturedParams.captured.body
        requestBody shouldNotBe null

        // Optional substring checks to verify key fields exist (avoid org.json parsing):
        requestBody!! shouldContain token
        requestBody shouldContain event.lowercase()
        requestBody shouldContain deliveryId

        // Assert #3: The result is success.
        result.isSuccess.shouldBeEqualTo(true)

        // Ensure we only called once
        coVerify(exactly = 1) { httpClient.request(any()) }
    }

    @Test
    fun trackMetric_givenHttpClientFails_expectCallbackFailure() = runTest {
        coEvery {
            httpClient.request(any())
        } returns Result.failure(Exception("Network error"))

        val result = pushDeliveryTracker.trackMetric("token", "OPENED", "deliveryId")

        result.isFailure.shouldBeEqualTo(true)
    }

    /**
     * Tests for the direct-HTTP fallback used when WorkManager is not available.
     * Mirrors the WorkManager success-removal / failure-preservation contract.
     */
    @Test
    fun asyncTrackMetric_givenHttpSuccess_expectPendingEntryRemovedByDeliveryId() = runTest {
        val token = String.random
        val deliveryId = String.random

        coEvery {
            mockDeliveryTracker.trackMetric(token, "Delivered", deliveryId)
        } returns Result.success(Unit)

        val asyncTracker = AsyncPushDeliveryTracker(
            deliveryTracker = mockDeliveryTracker,
            pendingStore = mockPendingStore
        )

        asyncTracker.trackMetric(
            token = token,
            event = "Delivered",
            deliveryId = deliveryId
        )

        coVerify(exactly = 1) { mockDeliveryTracker.trackMetric(token, "Delivered", deliveryId) }
        verify(exactly = 1) { mockPendingStore.remove(deliveryId) }
    }

    @Test
    fun asyncTrackMetric_givenHttpFailure_expectPendingEntryPreserved() = runTest {
        val token = String.random
        val deliveryId = String.random

        coEvery {
            mockDeliveryTracker.trackMetric(token, "Delivered", deliveryId)
        } returns Result.failure(Exception("boom"))

        val asyncTracker = AsyncPushDeliveryTracker(
            deliveryTracker = mockDeliveryTracker,
            pendingStore = mockPendingStore
        )

        asyncTracker.trackMetric(
            token = token,
            event = "Delivered",
            deliveryId = deliveryId
        )

        coVerify(exactly = 1) { mockDeliveryTracker.trackMetric(token, "Delivered", deliveryId) }
        verify(exactly = 0) { mockPendingStore.remove(any()) }
    }
}

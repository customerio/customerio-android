package io.customer.messagingpush

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.messagingpush.network.HttpClient
import io.customer.messagingpush.network.HttpRequestParams
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushDeliveryTrackingTest : IntegrationTest() {

    private val httpClient: HttpClient = mockk(relaxed = true)
    private val pushDeliveryTracker = PushDeliveryTrackerImpl()

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        // Override the httpClient in your DI so the SUT uses this mock.
                        overrideDependency<HttpClient>(httpClient)
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
}

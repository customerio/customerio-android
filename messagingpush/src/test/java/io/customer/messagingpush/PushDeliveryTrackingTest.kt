package io.customer.messagingpush

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.messagingpush.network.HttpClient
import io.customer.messagingpush.network.HttpRequestParams
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
    fun trackMetric_givenValidInputs_expectCorrectPathAndSuccessCallback() {
        val token = "token123"
        val event = "OPENED"
        val deliveryId = "delivery_abc"

        val capturedParams = slot<HttpRequestParams>()

        every {
            httpClient.request(capture(capturedParams), any())
        } answers {
            // Invoke the second arg (the callback) with success.
            secondArg<(Result<String>) -> Unit>().invoke(Result.success("Success"))
        }

        var callbackResult: Result<Unit>? = null

        pushDeliveryTracker.trackMetric(token, event, deliveryId) { result ->
            callbackResult = result
            // Return Unit to match the function signature
            Unit
        }

        // Assert #1: Confirm the correct path.
        capturedParams.captured.path shouldBeEqualTo "/track"

        // Assert #2: The body should not be null.
        val requestBody = capturedParams.captured.body
        requestBody shouldNotBe null

        // Optional substring checks to verify key fields exist (avoid org.json parsing):
        requestBody!! shouldContain token
        requestBody shouldContain event.lowercase()
        requestBody shouldContain deliveryId

        // Assert #3: The callback result is success.
        callbackResult!!.isSuccess.shouldBeEqualTo(true)

        // Ensure we only called once
        verify(exactly = 1) { httpClient.request(any(), any()) }
    }

    @Test
    fun trackMetric_givenHttpClientFails_expectCallbackFailure() {
        every {
            httpClient.request(any(), any())
        } answers {
            // Simulate failure
            secondArg<(Result<String>) -> Unit>().invoke(Result.failure(Exception("Network error")))
        }

        var callbackResult: Result<Unit>? = null

        pushDeliveryTracker.trackMetric("token", "OPENED", "deliveryId") { result ->
            callbackResult = result
            Unit
        }

        callbackResult!!.isFailure.shouldBeEqualTo(true)
    }
}

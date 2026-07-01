package io.customer.geofence.worker

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.communication.Event
import io.customer.sdk.core.network.CustomerIOHttpClient
import io.customer.sdk.core.network.HttpRequestParams
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotBeNull
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceEventTrackerTest : RobolectricTest() {

    private val httpClient: CustomerIOHttpClient = mockk(relaxed = true)

    private lateinit var tracker: GeofenceEventTrackerImpl

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { })
        tracker = GeofenceEventTrackerImpl(httpClient)
    }

    private fun entry(
        geofenceId: String = "biz-geofence-1",
        transition: Event.GeofenceTransition = Event.GeofenceTransition.ENTER,
        timestamp: Long = 1_234_567_890L,
        userId: String? = "user-42",
        transitionId: String = "transition-99"
    ) = PendingGeofenceDelivery(geofenceId, transition, timestamp, userId, transitionId)

    @Test
    fun trackEvent_givenEnterTransition_expectPostToTrackWithCorrectBody() = runTest {
        val capturedParams = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(capturedParams)) } returns Result.success("ok")

        val result = tracker.trackEvent(entry())

        result.isSuccess shouldBeEqualTo true
        capturedParams.captured.path shouldBeEqualTo "/track"
        val body = JSONObject(capturedParams.captured.body.shouldNotBeNull())
        body.getString("event") shouldBeEqualTo "Geofence Transition"
        body.getString("userId") shouldBeEqualTo "user-42"
        body.getString("timestamp") shouldBeEqualTo "2009-02-13T23:31:30.000Z"
        val props = body.getJSONObject("properties")
        props.getString("geofenceId") shouldBeEqualTo "biz-geofence-1"
        props.getString("transition") shouldBeEqualTo "enter"
        props.getString("transitionId") shouldBeEqualTo "transition-99"
        // Timestamp lives on the envelope (asserted above), not in properties.
        props.has("timestamp") shouldBeEqualTo false
        props.has("latitude") shouldBeEqualTo false
        props.has("longitude") shouldBeEqualTo false
    }

    @Test
    fun trackEvent_givenExitTransition_expectExitTransitionProperty() = runTest {
        val capturedParams = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(capturedParams)) } returns Result.success("ok")

        tracker.trackEvent(entry(transition = Event.GeofenceTransition.EXIT))

        val body = JSONObject(capturedParams.captured.body.shouldNotBeNull())
        body.getString("event") shouldBeEqualTo "Geofence Transition"
        body.getJSONObject("properties").getString("transition") shouldBeEqualTo "exit"
    }

    @Test
    fun trackEvent_givenHttpFailure_expectFailureResult() = runTest {
        val error = RuntimeException("boom")
        coEvery { httpClient.request(any()) } returns Result.failure(error)

        val result = tracker.trackEvent(entry())

        result.isFailure shouldBeEqualTo true
        result.exceptionOrNull() shouldBeEqualTo error
    }

    @Test
    fun trackEvent_givenNullUserIdEntry_expectFailureWithoutHttpCall() = runTest {
        // Precondition guard: anonymous entries belong on the foreground-flush path.
        // Callers must filter these out; if one slips through, fail loud rather than POST a userId-less event.
        val result = tracker.trackEvent(entry(userId = null))

        result.isFailure shouldBeEqualTo true
        (result.exceptionOrNull() is IllegalArgumentException) shouldBeEqualTo true
        coVerify(exactly = 0) { httpClient.request(any()) }
    }

    @Test
    fun trackEvent_givenContentTypeHeader_expectJsonHeader() = runTest {
        val capturedParams = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(capturedParams)) } returns Result.success("ok")

        tracker.trackEvent(entry())

        capturedParams.captured.headers["Content-Type"].shouldNotBeNull() shouldContain "application/json"
        coVerify(exactly = 1) { httpClient.request(any()) }
    }
}

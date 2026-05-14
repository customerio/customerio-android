package io.customer.location.geofence.worker

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.location.geofence.GeofenceLogger
import io.customer.sdk.communication.Event
import io.customer.sdk.core.network.CustomerIOHttpClient
import io.customer.sdk.core.network.HttpRequestParams
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)

    private lateinit var tracker: GeofenceEventTrackerImpl

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { })
        tracker = GeofenceEventTrackerImpl(httpClient, secureUserStore, logger)
    }

    @Test
    fun trackEvent_givenEnterTransition_expectPostToTrackWithCorrectBody() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        val capturedParams = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(capturedParams)) } returns Result.success("ok")

        val result = tracker.trackEvent(
            geofenceId = "biz-geofence-1",
            transition = Event.GeofenceTransition.ENTER,
            latitude = 37.7749,
            longitude = -122.4194,
            timestamp = 1_234_567_890L
        )

        result.isSuccess shouldBeEqualTo true
        capturedParams.captured.path shouldBeEqualTo "/track"
        val body = JSONObject(capturedParams.captured.body)
        body.getString("event") shouldBeEqualTo "GeoFence Entered"
        body.getString("userId") shouldBeEqualTo "user-42"
        val props = body.getJSONObject("properties")
        props.getString("geofence_id") shouldBeEqualTo "biz-geofence-1"
        props.getString("transition_type") shouldBeEqualTo "enter"
        props.getDouble("latitude") shouldBeEqualTo 37.7749
        props.getDouble("longitude") shouldBeEqualTo -122.4194
        props.getLong("timestamp") shouldBeEqualTo 1_234_567_890L
    }

    @Test
    fun trackEvent_givenExitTransition_expectExitedEventName() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        val capturedParams = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(capturedParams)) } returns Result.success("ok")

        tracker.trackEvent(
            geofenceId = "biz-geofence-2",
            transition = Event.GeofenceTransition.EXIT,
            latitude = 0.0,
            longitude = 0.0,
            timestamp = 0L
        )

        val body = JSONObject(capturedParams.captured.body)
        body.getString("event") shouldBeEqualTo "GeoFence Exited"
        body.getJSONObject("properties").getString("transition_type") shouldBeEqualTo "exit"
    }

    @Test
    fun trackEvent_givenAnonymousUser_expectSkippedWithoutHttpCall() = runTest {
        every { secureUserStore.getUserId() } returns null

        val result = tracker.trackEvent(
            geofenceId = "biz-geofence-3",
            transition = Event.GeofenceTransition.ENTER,
            latitude = 0.0,
            longitude = 0.0,
            timestamp = 0L
        )

        result.isSuccess shouldBeEqualTo true
        coVerify(exactly = 0) { httpClient.request(any()) }
    }

    @Test
    fun trackEvent_givenNullLatLng_expectBodyWithoutLatLng() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        val capturedParams = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(capturedParams)) } returns Result.success("ok")

        tracker.trackEvent(
            geofenceId = "biz-geofence-4",
            transition = Event.GeofenceTransition.ENTER,
            latitude = null,
            longitude = null,
            timestamp = 1L
        )

        val body = JSONObject(capturedParams.captured.body)
        val props = body.getJSONObject("properties")
        props.has("latitude") shouldBeEqualTo false
        props.has("longitude") shouldBeEqualTo false
    }

    @Test
    fun trackEvent_givenHttpFailure_expectFailureResult() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        val error = RuntimeException("boom")
        coEvery { httpClient.request(any()) } returns Result.failure(error)

        val result = tracker.trackEvent(
            geofenceId = "biz-geofence-5",
            transition = Event.GeofenceTransition.ENTER,
            latitude = 0.0,
            longitude = 0.0,
            timestamp = 0L
        )

        result.isFailure shouldBeEqualTo true
        result.exceptionOrNull() shouldBeEqualTo error
    }

    @Test
    fun trackEvent_givenContentTypeHeader_expectJsonHeader() = runTest {
        every { secureUserStore.getUserId() } returns "user-42"
        val capturedParams = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(capturedParams)) } returns Result.success("ok")

        tracker.trackEvent(
            geofenceId = "biz-geofence-6",
            transition = Event.GeofenceTransition.ENTER,
            latitude = 0.0,
            longitude = 0.0,
            timestamp = 0L
        )

        capturedParams.captured.headers["Content-Type"].shouldNotBeNull() shouldContain "application/json"
        coVerify(exactly = 1) { httpClient.request(any()) }
    }
}

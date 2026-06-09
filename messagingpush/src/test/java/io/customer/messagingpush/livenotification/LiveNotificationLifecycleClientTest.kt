package io.customer.messagingpush.livenotification

import io.customer.messagingpush.network.HttpClient
import io.customer.messagingpush.network.HttpMethod
import io.customer.messagingpush.network.HttpRequestException
import io.customer.messagingpush.network.HttpRequestParams
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class LiveNotificationLifecycleClientTest : IntegrationTest() {

    private val httpClient: HttpClient = mockk()
    private val client = LiveNotificationLifecycleClientImpl(httpClient)

    @Test
    fun register_buildsPutWithAndroidFcmBody() = runTest {
        val params = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(params)) } returns Result.success("")

        val result = client.registerForActivityType(
            activityType = "io.customer.liveactivities.deliverytracking",
            token = "tok-1",
            userId = "user-1"
        )

        result.isSuccess.shouldBeTrue()
        params.captured.method shouldBeEqualTo HttpMethod.PUT
        params.captured.path shouldBeEqualTo
            "/v1/live_activities/registration/io.customer.liveactivities.deliverytracking"
        val body = params.captured.body!!
        body shouldContain "tok-1"
        body shouldContain "\"os\":\"android\""
        body shouldContain "\"transport\":\"fcm\""
        body shouldContain "user-1"
    }

    @Test
    fun reportDismissed_buildsDeleteWithEmptyBody() = runTest {
        val params = slot<HttpRequestParams>()
        coEvery { httpClient.request(capture(params)) } returns Result.success("")

        client.reportDismissed("act-123")

        params.captured.method shouldBeEqualTo HttpMethod.DELETE
        params.captured.path shouldBeEqualTo "/v1/live_activities/act-123"
        params.captured.body shouldBeEqualTo "{}"
    }

    @Test
    fun send_retriesOn5xxUpToThreeAttempts() = runTest {
        coEvery { httpClient.request(any()) } returns Result.failure(HttpRequestException(503, "boom"))

        val result = client.reportDismissed("act-1")

        result.isFailure.shouldBeTrue()
        coVerify(exactly = 3) { httpClient.request(any()) }
    }

    @Test
    fun send_doesNotRetryOn4xx() = runTest {
        coEvery { httpClient.request(any()) } returns Result.failure(HttpRequestException(400, "bad request"))

        val result = client.reportDismissed("act-1")

        result.isFailure.shouldBeTrue()
        coVerify(exactly = 1) { httpClient.request(any()) }
    }

    @Test
    fun send_succeedsAfterTransientFailure() = runTest {
        coEvery { httpClient.request(any()) } returnsMany listOf(
            Result.failure(HttpRequestException(500, "x")),
            Result.success("")
        )

        val result = client.reportDismissed("act-1")

        result.isSuccess.shouldBeTrue()
        coVerify(exactly = 2) { httpClient.request(any()) }
    }
}

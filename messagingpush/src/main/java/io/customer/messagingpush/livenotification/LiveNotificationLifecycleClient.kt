package io.customer.messagingpush.livenotification

import io.customer.messagingpush.di.httpClient
import io.customer.messagingpush.network.HttpClient
import io.customer.messagingpush.network.HttpMethod
import io.customer.messagingpush.network.HttpRequestException
import io.customer.messagingpush.network.HttpRequestParams
import io.customer.sdk.core.di.SDKComponent
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Reports live-notification lifecycle to the Customer.io backend.
 *
 * Android has no push-to-start token like iOS; [registerForActivityType]
 * instead registers the device's FCM token per activity type so the backend
 * can push updates for it (`os = android`, `transport = fcm`).
 * [reportDismissed] mirrors iOS's terminal-state DELETE, fired when the user
 * dismisses the notification.
 */
internal interface LiveNotificationLifecycleClient {
    suspend fun registerForActivityType(activityType: String, token: String, userId: String): Result<Unit>

    suspend fun reportDismissed(activityId: String): Result<Unit>
}

internal class LiveNotificationLifecycleClientImpl(
    private val httpClient: HttpClient = SDKComponent.httpClient
) : LiveNotificationLifecycleClient {

    override suspend fun registerForActivityType(
        activityType: String,
        token: String,
        userId: String
    ): Result<Unit> {
        val body = JSONObject().apply {
            put("token", token)
            put("os", OS_ANDROID)
            put("transport", TRANSPORT_FCM)
            put("userId", userId)
        }
        return send(
            HttpRequestParams(
                path = "/v1/live_activities/registration/$activityType",
                method = HttpMethod.PUT,
                headers = JSON_HEADERS,
                body = body.toString()
            )
        )
    }

    override suspend fun reportDismissed(activityId: String): Result<Unit> {
        return send(
            HttpRequestParams(
                path = "/v1/live_activities/$activityId",
                method = HttpMethod.DELETE,
                headers = JSON_HEADERS,
                body = "{}"
            )
        )
    }

    /**
     * Sends [params], retrying transport failures and 5xx responses up to
     * [MAX_ATTEMPTS] with linear backoff. 4xx responses are not retried
     * (mirrors the iOS lifecycle client policy).
     */
    private suspend fun send(params: HttpRequestParams): Result<Unit> {
        var attempt = 0
        while (true) {
            attempt++
            val result = httpClient.request(params)
            if (result.isSuccess) return Result.success(Unit)

            val error = result.exceptionOrNull()
            val statusCode = (error as? HttpRequestException)?.statusCode
            val isClientError = statusCode != null && statusCode in 400..499
            if (isClientError || attempt >= MAX_ATTEMPTS) {
                return Result.failure(error ?: IllegalStateException("Live notification request failed"))
            }
            delay(BASE_BACKOFF_MS * attempt)
        }
    }

    companion object {
        private const val OS_ANDROID = "android"
        private const val TRANSPORT_FCM = "fcm"
        private const val MAX_ATTEMPTS = 3
        private const val BASE_BACKOFF_MS = 500L
        private val JSON_HEADERS = mapOf("Content-Type" to "application/json; charset=utf-8")
    }
}

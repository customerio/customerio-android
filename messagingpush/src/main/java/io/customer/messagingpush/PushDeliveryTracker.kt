package io.customer.messagingpush

import io.customer.messagingpush.di.httpClient
import io.customer.messagingpush.network.HttpClient
import io.customer.messagingpush.network.HttpRequestParams
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.util.EventNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

internal interface PushDeliveryTracker {
    suspend fun trackMetric(token: String, event: String, deliveryId: String): Result<Unit>
}

internal class PushDeliveryTrackerImpl : PushDeliveryTracker {

    private val httpClient: HttpClient
        get() = SDKComponent.httpClient

    /**
     * Tracks a metric by performing a single POST request with JSON.
     * Returns a `Result<Unit>`.
     */
    override suspend fun trackMetric(
        token: String,
        event: String,
        deliveryId: String
    ): Result<Unit> {
        val propertiesJson = JSONObject().apply {
            put("recipient", token)
            put("metric", event.lowercase())
            put("deliveryId", deliveryId)
        }
        val topLevelJson = JSONObject().apply {
            put("anonymousId", deliveryId)
            put("properties", propertiesJson)
            put("event", EventNames.METRIC_DELIVERY)
        }

        val params = HttpRequestParams(
            path = "/track",
            headers = mapOf(
                "Content-Type" to "application/json; charset=utf-8"
            ),
            body = topLevelJson.toString()
        )

        val result = httpClient.request(params)
        return result.map { /* we only need success/failure */ }
    }
}

internal class AsyncPushDeliveryTracker(
    private val deliveryTracker: PushDeliveryTracker
) {
    private val dispatcher: DispatchersProvider
        get() = SDKComponent.dispatchersProvider

    fun trackMetric(token: String, event: String, deliveryId: String) {
        CoroutineScope(dispatcher.background).launch {
            deliveryTracker.trackMetric(token, event, deliveryId)
        }
    }
}

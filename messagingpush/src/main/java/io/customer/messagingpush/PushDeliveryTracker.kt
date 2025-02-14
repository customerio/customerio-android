package io.customer.messagingpush

import io.customer.messagingpush.di.httpClient
import io.customer.messagingpush.network.HttpClient
import io.customer.messagingpush.network.HttpRequestParams
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.util.EventNames
import org.json.JSONObject

internal interface PushDeliveryTracker {
    fun trackMetric(token: String, event: String, deliveryId: String, onComplete: ((Result<Unit>) -> Unit?)? = null)
}

internal class PushDeliveryTrackerImpl : PushDeliveryTracker {

    private val httpClient: HttpClient
        get() = SDKComponent.httpClient

    /**
     * Tracks a metric by performing a single POST request with JSON.
     * Returns a `Result<Unit>`.
     */
    override fun trackMetric(
        token: String,
        event: String,
        deliveryId: String,
        onComplete: ((Result<Unit>) -> Unit?)?
    ) {
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

        // Perform request
        httpClient.request(params) { result ->
            val mappedResult = result.map { /* we only need success/failure */ }
            if (onComplete != null) {
                onComplete(mappedResult)
            }
        }
    }
}

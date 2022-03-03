package io.customer.messagingpush.api

import io.customer.messagingpush.data.request.Device
import io.customer.messagingpush.data.request.DeviceRequest
import io.customer.messagingpush.data.request.Metric
import io.customer.sdk.extensions.toResultUnit

/**
 * Wrapper around Retrofit to encapsulate Retrofit if we decide to change how we perform HTTP requests in the future.
 */
internal interface MessagingPushHttpClient {
    suspend fun registerDevice(identifier: String, device: Device): Result<Unit>
    suspend fun deleteDevice(identifier: String, deviceToken: String): Result<Unit>
    suspend fun trackPushMetrics(metric: Metric): Result<Unit>
}

internal class RetrofitMessagingPushHttpClientImpl(
    private val endpoints: MessagingPushEndpoints
) : MessagingPushHttpClient {

    override suspend fun registerDevice(identifier: String, device: Device): Result<Unit> {
        return endpoints.addDevice(identifier, DeviceRequest(device)).toResultUnit()
    }

    override suspend fun deleteDevice(identifier: String, deviceToken: String): Result<Unit> {
        return endpoints.removeDevice(identifier, deviceToken).toResultUnit()
    }

    override suspend fun trackPushMetrics(metric: Metric): Result<Unit> {
        return endpoints.trackMetric(metric).toResultUnit()
    }
}

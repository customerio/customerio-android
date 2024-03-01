package io.customer.sdk.api

import io.customer.sdk.api.service.CustomerIOService
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.request.*
import io.customer.sdk.data.request.DeliveryEvent
import io.customer.sdk.data.request.DeviceRequest
import io.customer.sdk.data.request.Event
import io.customer.sdk.data.request.Metric

/**
 * Wrapper around Retrofit to encapsulate Retrofit if we decide to change how we perform HTTP requests in the future.
 */
internal interface TrackingHttpClient {
    suspend fun identifyProfile(identifier: String, attributes: CustomAttributes): Result<Unit>
    suspend fun track(identifier: String, body: Event): Result<Unit>
    suspend fun registerDevice(identifier: String, device: Device): Result<Unit>
    suspend fun deleteDevice(identifier: String, deviceToken: String): Result<Unit>
    suspend fun trackPushMetrics(metric: Metric): Result<Unit>
    suspend fun trackDeliveryEvents(event: DeliveryEvent): Result<Unit>
}

internal class RetrofitTrackingHttpClient(
    private val retrofitService: CustomerIOService,
    private val httpRequestRunner: HttpRequestRunner
) : TrackingHttpClient {

    override suspend fun identifyProfile(
        identifier: String,
        attributes: CustomAttributes
    ): Result<Unit> {
        return httpRequestRunner.performAndProcessRequest {
            retrofitService.identifyCustomer(identifier, attributes)
        }
    }

    override suspend fun track(identifier: String, body: Event): Result<Unit> {
        return httpRequestRunner.performAndProcessRequest {
            retrofitService.track(identifier, body)
        }
    }

    override suspend fun registerDevice(identifier: String, device: Device): Result<Unit> {
        return httpRequestRunner.performAndProcessRequest {
            retrofitService.addDevice(identifier, DeviceRequest(device))
        }
    }

    override suspend fun deleteDevice(identifier: String, deviceToken: String): Result<Unit> {
        return httpRequestRunner.performAndProcessRequest {
            retrofitService.removeDevice(identifier, deviceToken)
        }
    }

    override suspend fun trackPushMetrics(metric: Metric): Result<Unit> {
        return httpRequestRunner.performAndProcessRequest {
            retrofitService.trackMetric(metric)
        }
    }

    override suspend fun trackDeliveryEvents(event: DeliveryEvent): Result<Unit> {
        return httpRequestRunner.performAndProcessRequest {
            retrofitService.trackDeliveryEvents(event)
        }
    }
}

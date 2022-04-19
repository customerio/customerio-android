package io.customer.sdk.api

import io.customer.sdk.api.service.CustomerIOService
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.request.Device
import io.customer.sdk.data.request.DeviceRequest
import io.customer.sdk.data.request.Event
import io.customer.sdk.data.request.Metric
import io.customer.sdk.repository.PreferenceRepository
import io.customer.sdk.util.Logger
import io.customer.sdk.util.SimpleTimer
import java.util.*

/**
 * Wrapper around Retrofit to encapsulate Retrofit if we decide to change how we perform HTTP requests in the future.
 */
internal interface TrackingHttpClient {
    suspend fun identifyProfile(identifier: String, attributes: CustomAttributes): Result<Unit>
    suspend fun track(identifier: String, body: Event): Result<Unit>
    suspend fun registerDevice(identifier: String, device: Device): Result<Unit>
    suspend fun deleteDevice(identifier: String, deviceToken: String): Result<Unit>
    suspend fun trackPushMetrics(metric: Metric): Result<Unit>
}

internal class RetrofitTrackingHttpClient(
    private val retrofitService: CustomerIOService,
    logger: Logger,
    retryPolicy: HttpRetryPolicy,
    prefsRepository: PreferenceRepository,
    retryPolicyTimer: SimpleTimer
) : BaseHttpClient(prefsRepository, logger, retryPolicy, retryPolicyTimer), TrackingHttpClient {

    override suspend fun identifyProfile(identifier: String, attributes: CustomAttributes): Result<Unit> {
        return performAndProcessRequest {
            retrofitService.identifyCustomer(identifier, attributes)
        }
    }

    override suspend fun track(identifier: String, body: Event): Result<Unit> {
        return performAndProcessRequest {
            retrofitService.track(identifier, body)
        }
    }

    override suspend fun registerDevice(identifier: String, device: Device): Result<Unit> {
        return performAndProcessRequest {
            retrofitService.addDevice(identifier, DeviceRequest(device))
        }
    }

    override suspend fun deleteDevice(identifier: String, deviceToken: String): Result<Unit> {
        return performAndProcessRequest {
            retrofitService.removeDevice(identifier, deviceToken)
        }
    }

    override suspend fun trackPushMetrics(metric: Metric): Result<Unit> {
        return performAndProcessRequest {
            retrofitService.trackMetric(metric)
        }
    }
}

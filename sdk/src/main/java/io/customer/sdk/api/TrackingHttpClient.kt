package io.customer.sdk.api

import io.customer.base.error.CustomerIOError
import io.customer.base.extenstions.add
import io.customer.base.extenstions.hasPassed
import io.customer.sdk.api.service.CustomerIOService
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.request.Device
import io.customer.sdk.data.request.DeviceRequest
import io.customer.sdk.data.request.Event
import io.customer.sdk.data.request.Metric
import io.customer.sdk.extensions.toResultUnit
import io.customer.sdk.repository.PreferenceRepository
import io.customer.sdk.util.Logger
import io.customer.sdk.util.SimpleTimer
import java.util.*
import java.util.concurrent.TimeUnit

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
    private val logger: Logger,
    private val retryPolicy: HttpRetryPolicy,
    private val prefsRepository: PreferenceRepository,
    private val retryPolicyTimer: SimpleTimer
) : TrackingHttpClient {

    override suspend fun identifyProfile(identifier: String, attributes: CustomAttributes): Result<Unit> {
        prefsRepository.httpRequestsPauseEnds?.let { httpPauseEnds ->
            if (!httpPauseEnds.hasPassed()) {
                logger.debug("HTTP request ignored because requests are still paused.")
                return Result.failure(CustomerIOError.HttpRequestsPaused())
            }
        }

        val response = retrofitService.identifyCustomer(identifier, attributes)

        if (response.isSuccessful) return Result.success(Unit)

        when (val statusCode = response.code()) {
            in 500 until 600 -> {
                val sleepTime = retryPolicy.nextSleepTime
                return if (sleepTime != null) {
                    logger.debug("Encountered $statusCode HTTP response. Sleeping $sleepTime seconds and then retrying.")

                    retryPolicyTimer.scheduleAndCancelPreviousSuspend(sleepTime) {
                        this.identifyProfile(identifier, attributes)
                    }
                } else {
                    pauseHttpRequests()

                    Result.failure(CustomerIOError.ServerDown())
                }
            }
            401 -> {
                pauseHttpRequests()

                return Result.failure(CustomerIOError.Unauthorized())
            }
            else -> {
                return Result.failure(RuntimeException(""))
            }
        }
    }

    override suspend fun track(identifier: String, body: Event): Result<Unit> {
        return retrofitService.track(identifier, body).toResultUnit()
    }

    override suspend fun registerDevice(identifier: String, device: Device): Result<Unit> {
        return retrofitService.addDevice(identifier, DeviceRequest(device)).toResultUnit()
    }

    override suspend fun deleteDevice(identifier: String, deviceToken: String): Result<Unit> {
        return retrofitService.removeDevice(identifier, deviceToken).toResultUnit()
    }

    override suspend fun trackPushMetrics(metric: Metric): Result<Unit> {
        return retrofitService.trackMetric(metric).toResultUnit()
    }

    // In certain scenarios, it makes sense for us to pause making any HTTP requests to the
    // Customer.io API. Because HTTP requests are performed by the background queue, there is
    // a chance that the background queue could make a lot of HTTP requests in
    // a short amount of time from lots of devices. This makes a performance impact on our API.
    // By pausing HTTP requests, we mitigate the chance of customer devices causing harm to our API.
    private fun pauseHttpRequests() {
        val minutesToPause = 5
        logger.info("All HTTP requests to Customer.io API have been paused for $minutesToPause minutes")

        val dateToEndPause = Date().add(minutesToPause, TimeUnit.MINUTES)

        prefsRepository.httpRequestsPauseEnds = dateToEndPause
    }
}

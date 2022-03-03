package io.customer.sdk.api

import io.customer.sdk.api.service.CustomerIOService
import io.customer.sdk.data.request.Event
import io.customer.sdk.extensions.toResultUnit

/**
 * Wrapper around Retrofit to encapsulate Retrofit if we decide to change how we perform HTTP requests in the future.
 */
internal interface TrackingHttpClient {
    suspend fun identifyProfile(identifier: String, attributes: Map<String, Any>): Result<Unit>
    suspend fun track(identifier: String, body: Event): Result<Unit>
}

internal class RetrofitTrackingHttpClient(
    private val retrofitService: CustomerIOService,
) : TrackingHttpClient {

    override suspend fun identifyProfile(identifier: String, attributes: Map<String, Any>): Result<Unit> {
        return retrofitService.identifyCustomer(identifier, attributes).toResultUnit()
    }

    override suspend fun track(identifier: String, body: Event): Result<Unit> {
        return retrofitService.track(identifier, body).toResultUnit()
    }
}

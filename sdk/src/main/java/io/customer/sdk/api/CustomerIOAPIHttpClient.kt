package io.customer.sdk.api

import io.customer.sdk.api.service.CustomerIOService
import io.customer.sdk.data.request.Event
import io.customer.sdk.extensions.toResultUnit

/**
 * Wrapper around Retrofit to encapsulate Retrofit if we decide to change how we perform HTTP requests in the future.
 */
interface CustomerIOAPIHttpClient {
    suspend fun track(identifier: String, body: Event): Result<Unit>
}

class RetrofitCustomerIOAPIHttpClient(
    private val retrofitService: CustomerIOService
) : CustomerIOAPIHttpClient {

    // only track() is implemented now. More will be added in future background queue work.

    override suspend fun track(identifier: String, body: Event): Result<Unit> {
        return retrofitService.track(identifier, body).toResultUnit()
    }
}

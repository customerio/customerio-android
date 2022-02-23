package io.customer.sdk.api

import io.customer.sdk.api.service.CustomerIOService
import io.customer.sdk.data.request.Event
import io.customer.sdk.extensions.toKResultUnit

internal interface CustomerIOAPIHttpClient {
    // suspend fun identifyCustomer(identifier: String, body: Map<String, Any>): Result<Unit>
    suspend fun track(identifier: String, body: Event): Result<Unit>
    // suspend fun registerDevice(identifier: String, body: DeviceRequest): Result<Unit>
    // suspend fun removeDevice(identifier: String, token: String): Result<Unit>
}

internal class RetrofitCustomerIOAPIHttpClient(
    private val retrofitService: CustomerIOService
) : CustomerIOAPIHttpClient {

    override suspend fun track(identifier: String, body: Event): Result<Unit> {
        return retrofitService.track(identifier, body).toKResultUnit()
    }
}

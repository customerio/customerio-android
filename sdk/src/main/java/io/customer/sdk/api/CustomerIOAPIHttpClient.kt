package io.customer.sdk.api

import io.customer.sdk.api.service.CustomerIOService
import io.customer.sdk.data.request.Device
import io.customer.sdk.data.request.DeviceRequest
import io.customer.sdk.data.request.Event
import io.customer.sdk.extensions.toResultUnit

/**
 * Wrapper around Retrofit to encapsulate Retrofit if we decide to change how we perform HTTP requests in the future.
 */
interface CustomerIOAPIHttpClient {
    suspend fun track(identifier: String, body: Event): Result<Unit>
    suspend fun registerDevice(identifier: String, device: Device): Result<Unit>
    suspend fun deleteDevice(identifier: String, deviceToken: String): Result<Unit>
}

class RetrofitCustomerIOAPIHttpClient(
    private val retrofitService: CustomerIOService
) : CustomerIOAPIHttpClient {

    override suspend fun track(identifier: String, body: Event): Result<Unit> {
        return retrofitService.track(identifier, body).toResultUnit()
    }

    override suspend fun registerDevice(identifier: String, device: Device): Result<Unit> {
        return retrofitService.addDevice(identifier, DeviceRequest(device)).toResultUnit()
    }

    override suspend fun deleteDevice(identifier: String, deviceToken: String): Result<Unit> {
        return retrofitService.removeDevice(identifier, deviceToken).toResultUnit()
    }
}

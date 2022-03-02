package io.customer.sdk.api.service

import io.customer.sdk.api.retrofit.CustomerIoCall
import io.customer.sdk.data.request.DeviceRequest
import io.customer.sdk.data.request.Event
import retrofit2.Response
import retrofit2.http.*

interface CustomerIOService {

    @JvmSuppressWildcards
    @PUT("api/v1/customers/{identifier}")
    fun identifyCustomer(
        @Path("identifier") identifier: String,
        @Body body: Map<String, Any>,
    ): CustomerIoCall<Unit>

    @JvmSuppressWildcards
    @POST("api/v1/customers/{identifier}/events")
    suspend fun track(
        @Path("identifier") identifier: String,
        @Body body: Event,
    ): Response<Unit>

    @JvmSuppressWildcards
    @PUT("api/v1/customers/{identifier}/devices")
    suspend fun addDevice(
        @Path("identifier") identifier: String,
        @Body body: DeviceRequest,
    ): Response<Unit>

    @JvmSuppressWildcards
    @DELETE("api/v1/customers/{identifier}/devices/{token}")
    suspend fun removeDevice(
        @Path("identifier") identifier: String,
        @Path("token") token: String,
    ): Response<Unit>
}

package io.customer.sdk.api.service

import io.customer.sdk.api.retrofit.CustomerIoCall
import io.customer.sdk.data.request.Device
import io.customer.sdk.data.request.Event
import retrofit2.http.*

internal interface CustomerService {

    @JvmSuppressWildcards
    @PUT("api/v1/customers/{identifier}")
    fun identifyCustomer(
        @Path("identifier") identifier: String,
        @Body body: Map<String, Any>,
    ): CustomerIoCall<Unit>

    @JvmSuppressWildcards
    @POST("api/v1/customers/{identifier}/events")
    fun track(
        @Path("identifier") identifier: String,
        @Body body: Event,
    ): CustomerIoCall<Unit>

    @JvmSuppressWildcards
    @PUT("api/v1/customers/{identifier}/devices")
    fun addDevice(
        @Path("identifier") identifier: String,
        @Body body: Device,
    ): CustomerIoCall<Unit>

    @JvmSuppressWildcards
    @DELETE("api/v1/customers/{identifier}/devices/{token}")
    fun removeDevice(
        @Path("identifier") identifier: String,
        @Path("token") token: String,
    ): CustomerIoCall<Unit>
}

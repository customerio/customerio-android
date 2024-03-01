package io.customer.sdk.api.service

import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.request.DeliveryEvent
import io.customer.sdk.data.request.DeviceRequest
import io.customer.sdk.data.request.Event
import io.customer.sdk.data.request.Metric
import retrofit2.Response
import retrofit2.http.*

internal interface CustomerIOService {

    @JvmSuppressWildcards
    @PUT("api/v1/customers/{identifier}")
    suspend fun identifyCustomer(
        @Path("identifier") identifier: String,
        @Body body: CustomAttributes
    ): Response<Unit>

    @JvmSuppressWildcards
    @POST("api/v1/customers/{identifier}/events")
    suspend fun track(
        @Path("identifier") identifier: String,
        @Body body: Event
    ): Response<Unit>

    @JvmSuppressWildcards
    @POST("push/events")
    suspend fun trackMetric(
        @Body body: Metric
    ): Response<Unit>

    @JvmSuppressWildcards
    @POST("api/v1/cio_deliveries/events")
    suspend fun trackDeliveryEvents(
        @Body body: DeliveryEvent
    ): Response<Unit>

    @JvmSuppressWildcards
    @PUT("api/v1/customers/{identifier}/devices")
    suspend fun addDevice(
        @Path("identifier") identifier: String,
        @Body body: DeviceRequest
    ): Response<Unit>

    @JvmSuppressWildcards
    @DELETE("api/v1/customers/{identifier}/devices/{token}")
    suspend fun removeDevice(
        @Path("identifier") identifier: String,
        @Path("token") token: String
    ): Response<Unit>
}

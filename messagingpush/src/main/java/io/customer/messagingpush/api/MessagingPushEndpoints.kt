package io.customer.messagingpush.api

import io.customer.messagingpush.data.request.DeviceRequest
import io.customer.messagingpush.data.request.Metric
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

internal interface MessagingPushEndpoints {
    @JvmSuppressWildcards
    @POST("push/events")
    suspend fun trackMetric(
        @Body body: Metric,
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

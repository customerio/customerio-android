package io.customer.sdk.api.service

import io.customer.sdk.data.request.Event
import retrofit2.Response
import retrofit2.http.*

interface CustomerIOService {

    @JvmSuppressWildcards
    @PUT("api/v1/customers/{identifier}")
    suspend fun identifyCustomer(
        @Path("identifier") identifier: String,
        @Body body: Map<String, Any>,
    ): Response<Unit>

    @JvmSuppressWildcards
    @POST("api/v1/customers/{identifier}/events")
    suspend fun track(
        @Path("identifier") identifier: String,
        @Body body: Event,
    ): Response<Unit>
}

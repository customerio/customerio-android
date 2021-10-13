package io.customer.sdk.api.service

import io.customer.sdk.api.retrofit.CustomerIoCall
import io.customer.sdk.data.request.Event
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

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
}

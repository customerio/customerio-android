package io.customer.sdk.api.service

import io.customer.sdk.api.retrofit.CustomerIoCall
import retrofit2.http.Body
import retrofit2.http.PUT
import retrofit2.http.Path

internal interface CustomerService {

    @JvmSuppressWildcards
    @PUT("api/v1/customers/{identifier}")
    fun identifyCustomer(
        @Path("identifier") identifier: String,
        @Body body: Map<String, Any>,
    ): CustomerIoCall<Unit>
}

package io.customer.sdk.api.service

import io.customer.sdk.api.retrofit.CustomerIoCall
import io.customer.sdk.data.request.Metric
import retrofit2.http.Body
import retrofit2.http.POST

internal interface PushService {
    @JvmSuppressWildcards
    @POST("push/events")
    fun trackMetric(
        @Body body: Metric,
    ): CustomerIoCall<Unit>
}

package io.customer.sdk.api.service

import io.customer.sdk.data.request.Metric
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface PushService {
    @JvmSuppressWildcards
    @POST("push/events")
    suspend fun trackMetric(
        @Body body: Metric,
    ): Response<Unit>
}

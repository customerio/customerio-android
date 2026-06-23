package io.customer.geofence.api

import io.customer.geofence.GeofenceJsonSerializer
import io.customer.sdk.core.network.CustomerIOHttpClient
import io.customer.sdk.core.network.HttpMethod
import io.customer.sdk.core.network.HttpRequestParams

internal interface GeofenceApiService {
    /** Returns the full (capped) set with no location sent. */
    suspend fun fetchGeofences(): Result<GeofenceApiResponse>
}

internal class GeofenceApiServiceImpl(
    private val httpClient: CustomerIOHttpClient,
    private val jsonSerializer: GeofenceJsonSerializer
) : GeofenceApiService {

    override suspend fun fetchGeofences(): Result<GeofenceApiResponse> {
        val params = HttpRequestParams(
            path = ENDPOINT_PATH,
            method = HttpMethod.GET,
            queryParams = emptyMap()
        )
        return httpClient.request(params).mapCatching { responseBody ->
            // Lenient at the wire boundary so the SDK doesn't pin a specific
            // type for `id` — accepts either numeric or quoted-string form.
            jsonSerializer.decode(GeofenceApiResponse.serializer(), responseBody, lenient = true)
        }
    }

    private companion object {
        private const val ENDPOINT_PATH = "/geofences/nearby"
    }
}

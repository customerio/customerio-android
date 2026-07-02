package io.customer.geofence.api

import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLocation
import io.customer.sdk.core.network.CustomerIOHttpClient
import io.customer.sdk.core.network.HttpMethod
import io.customer.sdk.core.network.HttpRequestParams

internal interface GeofenceApiService {
    /**
     * Fetches geofences. When [location] is null (FETCH_ALL) the backend returns the full (capped)
     * set and no location is sent. When non-null (NEARBY) the location is sent so the backend
     * returns the nearby set. The request carries no user identity, so the location is not
     * attributable to a user.
     */
    suspend fun fetchGeofences(location: GeofenceLocation? = null): Result<GeofenceApiResponse>
}

internal class GeofenceApiServiceImpl(
    private val httpClient: CustomerIOHttpClient,
    private val jsonSerializer: GeofenceJsonSerializer
) : GeofenceApiService {

    override suspend fun fetchGeofences(location: GeofenceLocation?): Result<GeofenceApiResponse> {
        val queryParams = location
            ?.let { mapOf("latitude" to it.latitude.toString(), "longitude" to it.longitude.toString()) }
            ?: emptyMap()
        val params = HttpRequestParams(
            path = ENDPOINT_PATH,
            method = HttpMethod.GET,
            queryParams = queryParams
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

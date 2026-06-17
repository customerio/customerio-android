package io.customer.geofence.api

import io.customer.geofence.GeofenceJsonSerializer
import io.customer.sdk.core.network.CustomerIOHttpClient
import io.customer.sdk.core.network.HttpMethod
import io.customer.sdk.core.network.HttpRequestParams

internal interface GeofenceApiService {
    /** Fetch-all: returns the full set with no location sent. */
    suspend fun fetchGeofences(): Result<GeofenceApiResponse>

    /** Nearby: sends the location (coarsened before it leaves the device) so the backend returns
     *  only nearby geofences. */
    suspend fun fetchGeofences(latitude: Double, longitude: Double): Result<GeofenceApiResponse>
}

internal class GeofenceApiServiceImpl(
    private val httpClient: CustomerIOHttpClient,
    private val jsonSerializer: GeofenceJsonSerializer
) : GeofenceApiService {

    override suspend fun fetchGeofences(): Result<GeofenceApiResponse> = request(emptyMap())

    override suspend fun fetchGeofences(latitude: Double, longitude: Double): Result<GeofenceApiResponse> =
        request(
            mapOf(
                "latitude" to GeofenceCoordinateCoarsener.coarsen(latitude).toString(),
                "longitude" to GeofenceCoordinateCoarsener.coarsen(longitude).toString()
            )
        )

    private suspend fun request(queryParams: Map<String, String>): Result<GeofenceApiResponse> {
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

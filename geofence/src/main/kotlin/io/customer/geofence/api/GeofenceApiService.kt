package io.customer.geofence.api

import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLocation
import io.customer.sdk.core.network.CustomerIOHttpClient
import io.customer.sdk.core.network.HttpMethod
import io.customer.sdk.core.network.HttpRequestParams
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal interface GeofenceApiService {
    /**
     * Fetches the nearest geofences to [location], sent in the request body. The request carries no
     * user identity, so the location is not attributable to a user.
     */
    suspend fun fetchGeofences(location: GeofenceLocation): Result<GeofenceApiResponse>
}

internal class GeofenceApiServiceImpl(
    private val httpClient: CustomerIOHttpClient,
    private val jsonSerializer: GeofenceJsonSerializer
) : GeofenceApiService {

    override suspend fun fetchGeofences(location: GeofenceLocation): Result<GeofenceApiResponse> {
        // `radius`/`limit` are optional server-side and omitted.
        val body = jsonSerializer.encode(
            GeofenceNearestRequest.serializer(),
            GeofenceNearestRequest(
                latitude = location.latitude,
                longitude = location.longitude,
                capabilities = listOf(POLYGON_CAPABILITY)
            )
        )
        val params = HttpRequestParams(
            path = ENDPOINT_PATH,
            method = HttpMethod.POST,
            headers = mapOf("Content-Type" to "application/json"),
            body = body
        )
        return httpClient.request(params).mapCatching { responseBody ->
            // Lenient at the wire boundary so the SDK doesn't pin a specific
            // type for `id` — accepts either numeric or quoted-string form.
            jsonSerializer.decode(GeofenceApiResponse.serializer(), responseBody, lenient = true)
        }
    }

    private companion object {
        private const val ENDPOINT_PATH = "/geofences/nearest"
        private const val POLYGON_CAPABILITY = "polygon-v1"
    }
}

/** Wire shape of the `POST /geofences/nearest` request body. */
@Serializable
private data class GeofenceNearestRequest(
    @SerialName("latitude")
    val latitude: Double,
    @SerialName("longitude")
    val longitude: Double,
    @SerialName("capabilities")
    val capabilities: List<String>
)

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
     * Fetches geofences. When [location] is non-null (NEARBY) it's sent with [radiusMeters] in the
     * request body so the backend returns the nearest set within that radius; when null (FETCH_ALL)
     * no body is sent and the backend returns the full (capped) set. The request carries no user
     * identity, so the location is not attributable to a user.
     */
    suspend fun fetchGeofences(
        location: GeofenceLocation? = null,
        radiusMeters: Double = 0.0
    ): Result<GeofenceApiResponse>
}

internal class GeofenceApiServiceImpl(
    private val httpClient: CustomerIOHttpClient,
    private val jsonSerializer: GeofenceJsonSerializer
) : GeofenceApiService {

    override suspend fun fetchGeofences(
        location: GeofenceLocation?,
        radiusMeters: Double
    ): Result<GeofenceApiResponse> {
        // `limit` is optional on the endpoint and omitted — the SDK caps the count locally.
        val body = location?.let {
            jsonSerializer.encode(
                GeofenceNearestRequest.serializer(),
                GeofenceNearestRequest(latitude = it.latitude, longitude = it.longitude, radius = radiusMeters)
            )
        }
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
    }
}

/** Wire shape of the `POST /geofences/nearest` request body. */
@Serializable
private data class GeofenceNearestRequest(
    @SerialName("latitude")
    val latitude: Double,
    @SerialName("longitude")
    val longitude: Double,
    @SerialName("radius")
    val radius: Double
)

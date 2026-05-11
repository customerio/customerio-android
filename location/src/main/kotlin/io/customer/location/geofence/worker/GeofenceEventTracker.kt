package io.customer.location.geofence.worker

import io.customer.sdk.communication.Event
import io.customer.sdk.core.network.CustomerIOHttpClient
import io.customer.sdk.core.network.HttpRequestParams
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.data.store.SecureUserStore
import io.customer.sdk.util.EventNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Sends a geofence transition event via direct HTTP, bypassing the analytics pipeline.
 * Used by [GeofenceEventWorker] (and the async fallback below) so delivery survives
 * process death and does not depend on full SDK initialization.
 */
internal interface GeofenceEventTracker {
    suspend fun trackEvent(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        latitude: Double?,
        longitude: Double?,
        timestamp: Long
    ): Result<Unit>
}

internal class GeofenceEventTrackerImpl(
    private val httpClient: CustomerIOHttpClient,
    private val secureUserStore: SecureUserStore
) : GeofenceEventTracker {

    override suspend fun trackEvent(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        latitude: Double?,
        longitude: Double?,
        timestamp: Long
    ): Result<Unit> {
        val eventName = when (transition) {
            Event.GeofenceTransition.ENTER -> EventNames.GEOFENCE_ENTERED
            Event.GeofenceTransition.EXIT -> EventNames.GEOFENCE_EXITED
        }

        val propertiesJson = JSONObject().apply {
            put("geofence_id", geofenceId)
            put("transition_type", transition.name.lowercase())
            latitude?.let { put("latitude", it) }
            longitude?.let { put("longitude", it) }
            put("timestamp", timestamp)
        }
        val bodyJson = JSONObject().apply {
            put("properties", propertiesJson)
            put("event", eventName)
            // Cached userId so direct-HTTP delivery works without full SDK init.
            secureUserStore.getUserId()?.let { put("userId", it) }
        }

        val params = HttpRequestParams(
            path = "/track",
            headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
            body = bodyJson.toString()
        )

        return httpClient.request(params).map { /* success/failure only */ }
    }
}

/** Async fallback when WorkManager is unavailable. Fire-and-forget; does not survive process death. */
internal class AsyncGeofenceEventTracker(
    private val tracker: GeofenceEventTracker,
    private val dispatcher: DispatchersProvider
) {
    fun trackEvent(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        latitude: Double?,
        longitude: Double?,
        timestamp: Long
    ) {
        CoroutineScope(dispatcher.background).launch {
            tracker.trackEvent(geofenceId, transition, latitude, longitude, timestamp)
        }
    }
}

package io.customer.geofence.worker

import io.customer.geofence.GeofenceLogger
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.geofence.store.withFreshestEventData
import io.customer.sdk.core.network.CustomerIOHttpClient
import io.customer.sdk.core.network.HttpRequestParams
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.core.util.Iso8601TimestampFormatter
import io.customer.sdk.data.store.PendingDeliveryStore
import io.customer.sdk.data.store.sendRemoveOnSuccess
import io.customer.sdk.util.EventNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Direct HTTP, bypassing the analytics pipeline, so the worker and async fallback don't need full
 * SDK initialization. Sends as the queue-time [PendingGeofenceDelivery.userId] so a later sign-in
 * can't reattribute it.
 */
internal interface GeofenceEventTracker {
    suspend fun trackEvent(entry: PendingGeofenceDelivery): Result<Unit>
}

internal class GeofenceEventTrackerImpl(
    private val httpClient: CustomerIOHttpClient,
    private val regionStore: GeofenceRegionStore
) : GeofenceEventTracker {

    override suspend fun trackEvent(entry: PendingGeofenceDelivery): Result<Unit> {
        val userId = entry.userId
        if (userId.isNullOrEmpty()) {
            return Result.failure(
                IllegalArgumentException("trackEvent precondition: entry.userId is null or empty; defer to foreground flush")
            )
        }

        val event = entry.withFreshestEventData(regionStore.getCachedRegion(entry.geofenceId))
        val bodyJson = JSONObject().apply {
            put("properties", JSONObject(event.toEventProperties()))
            Iso8601TimestampFormatter.fromUnixSeconds(entry.timestamp)?.let { put("timestamp", it) }
            put("event", EventNames.GEOFENCE_TRANSITION)
            put("userId", userId)
        }

        val params = HttpRequestParams(
            path = "/track",
            headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
            body = bodyJson.toString()
        )

        return httpClient.request(params).map { }
    }
}

/** Fallback when WorkManager is unavailable; does not survive process death. */
internal class AsyncGeofenceEventTracker(
    private val tracker: GeofenceEventTracker,
    private val pendingStore: PendingDeliveryStore<PendingGeofenceDelivery>,
    private val dispatcher: DispatchersProvider,
    private val logger: GeofenceLogger
) {
    fun trackEvent(entry: PendingGeofenceDelivery) {
        if (entry.userId.isNullOrEmpty()) {
            logger.logEventDeliveryDeferredAnonymous(entry.geofenceId, entry.transition.name)
            return
        }
        CoroutineScope(dispatcher.background).launch {
            try {
                pendingStore.sendRemoveOnSuccess(entry, ::isRetryableDeliveryFailure) {
                    tracker.trackEvent(entry)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Ad-hoc scope with no exception handler: an escape would crash the host. The entry
                // stays in the store for the foreground flush.
                logger.logAsyncDeliveryFailed(entry.geofenceId, entry.transition.name, e.message)
            }
        }
    }
}

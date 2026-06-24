package io.customer.geofence.worker

import io.customer.geofence.GeofenceLogger
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.core.network.CustomerIOHttpClient
import io.customer.sdk.core.network.HttpRequestParams
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.core.util.Iso8601TimestampFormatter
import io.customer.sdk.data.store.PendingDeliveryStore
import io.customer.sdk.data.store.claimSendRestore
import io.customer.sdk.util.EventNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Sends a geofence transition event via direct HTTP, bypassing the analytics pipeline.
 * Used by [GeofenceEventWorker] (and the async fallback below) so delivery survives
 * process death and does not depend on full SDK initialization.
 *
 * Precondition: [entry] must carry a non-null [PendingGeofenceDelivery.userId]
 * (the user identified when the transition fired, snapshotted at queue time so
 * sign-out + sign-in cannot reattribute). Anonymous entries belong on the
 * foreground-flush path and must not be passed here.
 */
internal interface GeofenceEventTracker {
    suspend fun trackEvent(entry: PendingGeofenceDelivery): Result<Unit>
}

internal class GeofenceEventTrackerImpl(
    private val httpClient: CustomerIOHttpClient
) : GeofenceEventTracker {

    override suspend fun trackEvent(entry: PendingGeofenceDelivery): Result<Unit> {
        val userId = entry.userId
        if (userId.isNullOrEmpty()) {
            return Result.failure(
                IllegalArgumentException("trackEvent precondition: entry.userId is null or empty; defer to foreground flush")
            )
        }

        val bodyJson = JSONObject().apply {
            put("properties", JSONObject(entry.toEventProperties()))
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

/**
 * Async fallback when WorkManager is unavailable. Fire-and-forget; does not
 * survive process death. Uses the same [claimSendRestore] contract as the
 * worker — claim before sending — so that if the foreground flush fires while
 * this HTTP call is in flight, only one channel delivers: a lost claim skips
 * the send, and a failed send restores the entry for the flush to retry.
 *
 * Entries with a null [PendingGeofenceDelivery.userId] are left untouched here;
 * the foreground flush handles them via the analytics pipeline's anonymousId.
 */
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
            pendingStore.claimSendRestore(entry) {
                tracker.trackEvent(entry)
            }
        }
    }
}

package io.customer.location

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.location.store.LocationPreferenceStore
import io.customer.location.sync.LocationSyncFilter
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.pipeline.DataPipeline
import io.customer.sdk.core.pipeline.IdentifyHook
import io.customer.sdk.core.util.Logger
import io.customer.sdk.util.EventNames
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coordinates all location state management: persistence, restoration,
 * identify context enrichment, and sending location track events.
 *
 * Location reaches the backend through two independent paths:
 *
 * 1. **Identify context enrichment** — implements [IdentifyHook].
 *    Every identify() call enriches the event context with the latest
 *    location coordinates. This is unfiltered — a new user always gets
 *    the device's current location on their profile immediately.
 *
 * 2. **"CIO Location Update" track event** — sent via [DataPipeline.track].
 *    Gated by a userId check and a sync filter (24h / 1km threshold)
 *    to avoid redundant events. This creates a discrete event in the
 *    user's activity timeline for journey/segment triggers.
 *
 * Profile switch handling is intentionally not tracked here.
 * On clearIdentify(), [resetContext] clears all state (cache, persistence,
 * sync filter) synchronously during analytics.reset(). On identify(), the
 * new user's profile receives the location via path 1 regardless of the
 * sync filter's state.
 */
internal class LocationTracker(
    dataPipeline: DataPipeline?,
    private val locationPreferenceStore: LocationPreferenceStore,
    private val locationSyncFilter: LocationSyncFilter,
    private val eventBus: EventBus,
    private val logger: Logger
) : IdentifyHook {

    // WeakReference prevents this hook (retained by IdentifyHookRegistry
    // inside a Segment plugin) from keeping CustomerIO alive after SDK
    // cleanup. The strong reference lives in CustomerIO.instance — as long
    // as the SDK is initialized, the weak ref is valid.
    private val dataPipelineRef = WeakReference(dataPipeline)

    // Analytics location: enriches identify context, gates the ON_APP_START re-fetch,
    // and is persisted. Set only by the tracking path, so silent fixes stay out of analytics.
    @Volatile
    internal var trackedLocation: LocationCoordinates? = null
        private set

    // Latest fix from any source (incl. silent geofence fixes), for cross-module reads
    // via [LocationServices.getLastKnownLocation]. In-memory only; never analytics.
    @Volatile
    internal var lastKnownLocation: LocationCoordinates? = null
        private set

    // Guards the two locations against a background restore landing after a newer fix or a
    // sign-out. Never held across a Keystore call.
    private val stateLock = Any()
    private var resetCount = 0

    // The background restore started at init; readers that need its result wait on it.
    @Volatile
    private var restoreJob: Job? = null

    override fun getIdentifyContext(): Map<String, Any> {
        // Runs on Segment's pipeline thread, so an identify right after init still gets the
        // persisted location. Capped like Segment's own WaitingPlugin; a stalled Keystore
        // couldn't decrypt it anyway.
        awaitRestoreBlocking()
        val location = trackedLocation ?: return emptyMap()
        return mapOf(
            "location_latitude" to location.latitude,
            "location_longitude" to location.longitude
        )
    }

    /**
     * Called synchronously by analytics.reset() during clearIdentify.
     * Clears all location state: in-memory cache, persisted coordinates,
     * and sync filter — similar to how device tokens and other per-user
     * state are cleared on reset. This runs before ResetEvent is published,
     * guaranteeing no stale data is available for a subsequent identify().
     */
    override fun resetContext() {
        synchronized(stateLock) {
            resetCount++
            trackedLocation = null
            lastKnownLocation = null
        }
        locationPreferenceStore.clearCachedLocation()
        locationSyncFilter.clearSyncedData()
        logger.debug("Location state reset")
    }

    /** Runs [restorePersistedLocation] in [scope], keeping its Keystore decrypts off the caller's thread. */
    fun restorePersistedLocationInBackground(scope: CoroutineScope) {
        restoreJob = scope.launch { restorePersistedLocation() }
    }

    /** Waits for the restore started by [restorePersistedLocationInBackground], if any. */
    suspend fun awaitRestore() {
        restoreJob?.join()
    }

    /** [lastKnownLocation] once the init restore has landed. Blocks, so never call it on the main thread. */
    fun awaitLastKnownLocation(): LocationCoordinates? {
        awaitRestoreBlocking()
        return lastKnownLocation
    }

    private fun awaitRestoreBlocking() {
        val job = restoreJob ?: return
        if (job.isCompleted) return
        runBlocking { withTimeoutOrNull(RESTORE_WAIT_MILLIS) { job.join() } }
    }

    /**
     * Reads persisted cached location from the preference store and sets the
     * in-memory cache so that identify events have location context
     * immediately after SDK restart.
     */
    fun restorePersistedLocation() {
        val resetsAtStart = synchronized(stateLock) { resetCount }
        val lat = locationPreferenceStore.getCachedLatitude() ?: return
        val lng = locationPreferenceStore.getCachedLongitude() ?: return
        val restored = LocationCoordinates(latitude = lat, longitude = lng)
        synchronized(stateLock) {
            // A sign-out while decrypting must not bring the old user's location back, and a
            // fix that arrived meanwhile is newer than the persisted one.
            if (resetCount != resetsAtStart) return
            if (trackedLocation == null) trackedLocation = restored
            if (lastKnownLocation == null) lastKnownLocation = restored
        }
        logger.debug("Restored persisted location: lat=$lat, lng=$lng")
    }

    /**
     * Processes an incoming location: caches in memory, persists
     * coordinates, and attempts to send a location track event.
     */
    fun onLocationReceived(
        latitude: Double,
        longitude: Double,
        fixElapsedRealtimeMillis: Long? = null
    ) {
        logger.debug("Location update received: lat=$latitude, lng=$longitude")

        val location = LocationCoordinates(latitude = latitude, longitude = longitude)
        synchronized(stateLock) {
            trackedLocation = location
            lastKnownLocation = location
        }
        locationPreferenceStore.saveCachedLocation(latitude, longitude)

        trySendLocationTrack(latitude, longitude)
        eventBus.publish(
            Event.LocationAcquired(latitude = latitude, longitude = longitude)
        )
        eventBus.publish(
            Event.LocationFixAcquired(
                latitude = latitude,
                longitude = longitude,
                fixElapsedRealtimeMillis = fixElapsedRealtimeMillis
            )
        )
    }

    /**
     * Like [onLocationReceived] but with no analytics side effects: no track event and
     * [trackedLocation]/persistence are left untouched, so it never reaches identify
     * context. Updates [lastKnownLocation] so geofencing can read it back.
     */
    fun onLocationReceivedWithoutTracking(
        latitude: Double,
        longitude: Double,
        fixElapsedRealtimeMillis: Long? = null
    ) {
        logger.debug("Location update received (geofence-only, not tracked): lat=$latitude, lng=$longitude")

        synchronized(stateLock) {
            lastKnownLocation = LocationCoordinates(latitude = latitude, longitude = longitude)
        }
        eventBus.publish(
            Event.LocationAcquired(latitude = latitude, longitude = longitude)
        )
        eventBus.publish(
            Event.LocationFixAcquired(
                latitude = latitude,
                longitude = longitude,
                fixElapsedRealtimeMillis = fixElapsedRealtimeMillis
            )
        )
    }

    /**
     * Called when a user is identified. Attempts to sync the cached
     * location as a track event for the newly identified user.
     *
     * The identify event itself already carries location via
     * [getIdentifyContext] — this method handles the supplementary
     * "CIO Location Update" track event, subject to the sync filter.
     */
    fun onUserIdentified() {
        syncCachedLocationIfNeeded()
    }

    /**
     * Re-evaluates the cached location for sending.
     * Called on identify (via [onUserIdentified]) and on cold start
     * (via replayed UserChangedEvent) to handle cases where a location
     * was cached but not yet sent for the current user.
     */
    internal fun syncCachedLocationIfNeeded() {
        val lat = locationPreferenceStore.getCachedLatitude() ?: return
        val lng = locationPreferenceStore.getCachedLongitude() ?: return

        logger.debug("Re-evaluating cached location: lat=$lat, lng=$lng")
        trySendLocationTrack(lat, lng)
    }

    /**
     * Applies the userId gate and sync filter, then sends a location
     * track event via [DataPipeline] if both pass.
     */
    private fun trySendLocationTrack(latitude: Double, longitude: Double) {
        val pipeline = dataPipelineRef.get() ?: return
        if (!pipeline.isUserIdentified) return
        if (!locationSyncFilter.filterAndRecord(latitude, longitude)) return

        logger.debug("Sending location track: lat=$latitude, lng=$longitude")
        pipeline.track(
            name = EventNames.LOCATION_UPDATE,
            properties = mapOf(
                "latitude" to latitude,
                "longitude" to longitude
            )
        )
    }

    private companion object {
        // Segment's WaitingPlugin default.
        const val RESTORE_WAIT_MILLIS = 30_000L
    }
}

/**
 * Last-known device location, exposed for cross-module reads via
 * [LocationServices.getLastKnownLocation].
 */
@InternalCustomerIOApi
data class LocationCoordinates(
    val latitude: Double,
    val longitude: Double
) {
    // The API validator checks nested classes individually — the companion doesn't inherit the marker.
    @InternalCustomerIOApi
    companion object {
        /**
         * Validates that latitude is within [-90, 90] and longitude is within [-180, 180].
         * Also rejects NaN and Infinity values.
         */
        fun isValid(latitude: Double, longitude: Double): Boolean {
            if (latitude.isNaN() || latitude.isInfinite()) return false
            if (longitude.isNaN() || longitude.isInfinite()) return false
            return latitude in -90.0..90.0 && longitude in -180.0..180.0
        }
    }
}

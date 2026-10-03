package io.customer.geofence.polygon

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import io.customer.geofence.GeofenceLogger

/**
 * Listens for fixes other apps already pay for; `PRIORITY_PASSIVE` never turns a sensor on and
 * guarantees nothing. One long-lived request while any polygon is registered, with no session state.
 */
internal interface PolygonPassiveMonitor {
    /**
     * Idempotent: GMS replaces a request whose `PendingIntent` compares equal, and every call here
     * builds an equal one, so repeated starts leave exactly one registration.
     */
    fun start()

    fun stop()

    /** Whether a delivered passive fix still belongs to a live registration. */
    fun isArmed(): Boolean
}

internal class GmsPolygonPassiveMonitor(
    private val context: Context,
    private val client: FusedLocationProviderClient,
    private val logger: GeofenceLogger
) : PolygonPassiveMonitor {
    @SuppressLint("MissingPermission")
    override fun start() {
        val pendingIntent = pendingIntent(FLAG_DEFAULT) ?: return
        runCatching {
            client.requestLocationUpdates(passiveRequest(), pendingIntent)
                .addOnSuccessListener { logger.logPolygonPassiveStarted() }
                .addOnFailureListener { logger.logPolygonPassiveFailed(it.message) }
        }.onFailure { logger.logPolygonPassiveFailed(it.message) }
    }

    override fun stop() {
        // NO_CREATE, so a stop cannot mint the very request it is trying to remove.
        val pendingIntent = pendingIntent(FLAG_NO_CREATE) ?: return
        runCatching {
            client.removeLocationUpdates(pendingIntent)
                .addOnSuccessListener { logger.logPolygonPassiveStopped() }
                .addOnFailureListener { logger.logPolygonPassiveFailed(it.message) }
        }.onFailure { logger.logPolygonPassiveFailed(it.message) }
        // Cancelled as well as removed so [isArmed] turns false: removal leaves the intent alive, and
        // a fix already dispatched still arrives.
        pendingIntent.cancel()
    }

    /**
     * Read at delivery, since the OS dispatches before the receiver runs. The system owns the intent,
     * so a cold-process delivery under a live registration is still admitted.
     */
    override fun isArmed(): Boolean = pendingIntent(FLAG_NO_CREATE) != null

    private fun pendingIntent(flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        PENDING_INTENT_REQUEST_CODE,
        Intent(context, PolygonPassiveReceiver::class.java),
        flags
    )

    internal companion object {
        /**
         * Distinct from [PolygonApproachMonitor]'s so the intents stay apart if they share a
         * receiver.
         */
        private const val PENDING_INTENT_REQUEST_CODE = 47303

        private val FLAG_DEFAULT = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        private val FLAG_NO_CREATE = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE

        /** Throttles process wakes: a navigating maps app requests a fix every second. */
        internal const val MINIMUM_INTERVAL_MS = 60_000L
        private const val NOMINAL_INTERVAL_MS = 5 * 60_000L

        internal fun passiveRequest(): LocationRequest = LocationRequest.Builder(
            Priority.PRIORITY_PASSIVE,
            NOMINAL_INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(MINIMUM_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()
    }
}

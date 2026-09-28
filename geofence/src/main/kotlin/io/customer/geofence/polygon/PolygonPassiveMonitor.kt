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
 * Listens for fixes other apps are already paying for, while any polygon is registered.
 *
 * `PRIORITY_PASSIVE` never turns a sensor on and guarantees nothing: with no other app requesting
 * location, no fix arrives. It can only narrow the gap between periodic re-checks, which
 * WorkManager floors at 15 minutes.
 *
 * No session state, unlike [PolygonApproachMonitor]: one long-lived request while any polygon is
 * registered. A delivery after a user change is refused by the controller's generation check.
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
        // Non-null in practice with FLAG_UPDATE_CURRENT; the type is nullable for NO_CREATE's sake.
        val pendingIntent = pendingIntent(FLAG_DEFAULT) ?: return
        runCatching {
            client.requestLocationUpdates(passiveRequest(), pendingIntent)
                .addOnSuccessListener { logger.logPolygonPassiveStarted() }
                .addOnFailureListener { logger.logPolygonPassiveFailed(it.message) }
        }.onFailure { logger.logPolygonPassiveFailed(it.message) }
    }

    override fun stop() {
        // NO_CREATE, so a stop cannot mint the very request it is trying to remove. Absent means
        // nothing was ever registered, which is the ordinary case for a build with no polygons.
        val pendingIntent = pendingIntent(FLAG_NO_CREATE) ?: return
        runCatching {
            client.removeLocationUpdates(pendingIntent)
                .addOnSuccessListener { logger.logPolygonPassiveStopped() }
                .addOnFailureListener { logger.logPolygonPassiveFailed(it.message) }
        }.onFailure { logger.logPolygonPassiveFailed(it.message) }
        // Cancelled as well as removed, which is what makes [isArmed] answerable. Removing the
        // request stops new deliveries but leaves the intent alive, so a fix already dispatched
        // still arrives and nothing it can read says the session ended.
        pendingIntent.cancel()
    }

    /**
     * Whether this registration is still live. Read at delivery: the OS dispatches a passive fix
     * before the receiver runs, so there is no earlier point to capture a token. The system owns the
     * intent, so a cold-process delivery under a live registration is still admitted.
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
         * Distinct from [PolygonApproachMonitor]'s. Not required while the intents target different
         * receivers, but keeps the two `PendingIntent`s apart if that ever changes.
         */
        private const val PENDING_INTENT_REQUEST_CODE = 47303

        private val FLAG_DEFAULT = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        private val FLAG_NO_CREATE = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE

        /**
         * Throttles process wakes: a navigating maps app requests a fix every second, and each would
         * otherwise reach the evaluator. Sensor cost is already bounded by the controller's 30 s
         * fresh-fix cooldown.
         *
         * A passive `PendingIntent` request does not survive reboot; boot restore starts it again, so
         * `polygon.passive.started` after every boot is expected.
         */
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

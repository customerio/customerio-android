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
 * `PRIORITY_PASSIVE` never turns a sensor on. It asks the OS to hand over location that some other
 * app requested anyway, so it costs nothing beyond the delivery, and it guarantees nothing: on a
 * device where no other app asks for location, no fix ever arrives. That asymmetry is the whole
 * argument for it. The periodic re-check is bounded below by WorkManager's 15 minute floor, and a
 * passive fix can land at any moment inside that window, so it can only narrow the gap.
 *
 * Deliberately far simpler than [PolygonApproachMonitor], and the difference is worth stating
 * because that class cost six defects. There is no session here: no deadline, no sample count, no
 * per-session identity. The registration's lifetime is "some polygon is registered", which is a
 * single long-lived request, so nothing needs to tell two generations of it apart. The delivering
 * receiver reads the live generation from the store instead, and a delivery that arrives after a
 * user change is refused by the controller's own generation check rather than by bookkeeping here.
 */
internal interface PolygonPassiveMonitor {
    /**
     * Idempotent: GMS replaces a request whose `PendingIntent` compares equal, and every call here
     * builds an equal one, so repeated starts leave exactly one registration.
     */
    fun start()

    fun stop()
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
    }

    private fun pendingIntent(flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        PENDING_INTENT_REQUEST_CODE,
        Intent(context, PolygonPassiveReceiver::class.java),
        flags
    )

    internal companion object {
        /**
         * Distinct from [PolygonApproachMonitor]'s by convention with it, not because equality
         * depends on it: `filterEquals` compares the component among other things, and these two
         * intents target different receivers, so they could never be equal even sharing a code.
         * Kept distinct anyway, since `PendingIntent` identity does include the request code and a
         * future intent that stopped differing by component would then still be safe.
         */
        private const val PENDING_INTENT_REQUEST_CODE = 47303

        private val FLAG_DEFAULT = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        private val FLAG_NO_CREATE = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE

        /**
         * The interval is a ceiling on what we accept, not a request for anything: passive delivers
         * only what another app already asked for. [MINIMUM_INTERVAL_MS] is the one number that
         * matters, because a maps app in navigation requests fixes every second and every one of
         * them would otherwise reach the evaluator.
         *
         * 60 s is **unmeasured**, a starting number. It throttles process wakes rather than sensor
         * use: the sensor cost of a delivery is bounded already, since an undecided verdict can
         * only buy a precise fix once per the controller's 30 s cooldown. Revisit it with a capture
         * that shows the delivery rate, not by reasoning.
         *
         * A passive request with a `PendingIntent` does not survive a reboot, unlike a geofence.
         * Boot restore re-registers and reconcile starts this again, so a capture will show
         * `polygon.passive.started` after every boot and that is expected, not a restart loop.
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

package io.customer.geofence.polygon

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import io.customer.geofence.GeofenceLogger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps a low-power, displacement-gated location request active while polygons are registered.
 * Its PendingIntent survives process death and gives the SDK a second chance to wake before a
 * delayed enclosing-circle callback. Exact polygon decisions remain in [PolygonLocationEngine].
 */
internal class PolygonApproachMonitor(
    context: Context,
    private val client: FusedLocationProviderClient,
    private val logger: GeofenceLogger,
    backgroundContext: CoroutineContext
) {
    private val lock = Any()
    private var desired = false
    private var userStateGeneration: Long? = null
    private var activePendingIntent: PendingIntent? = null
    private val applicationContext = context.applicationContext
    private val retryScope = CoroutineScope(SupervisorJob() + backgroundContext)
    private var registrationRetryAttempt = 0
    private var registrationRetryJob: Job? = null
    private val removalRetryAttempts = mutableMapOf<PendingIntent, Int>()
    private val removalRetryJobs = mutableMapOf<PendingIntent, Job>()

    /**
     * Asks Play services for approach fixes, best-effort.
     *
     * Deliberately **not** `@RequiresPermission`: holding a location permission is not a
     * precondition callers must satisfy. Permission can be revoked between any check and this call,
     * and the three callers (a broadcast in a cold process, and two catalog-reconciliation paths)
     * cannot meaningfully hold one either way. Instead the missing-permission case is a handled
     * outcome: the request fails with [SecurityException], [retryRegistration] logs it and clears
     * `desired` so nothing is retried and no fixes are expected. Annotating it would push a
     * requirement onto callers that this class does not actually have, and the only honest way to
     * satisfy it there would be to suppress the warning at each site.
     */
    fun start(expectedUserStateGeneration: Long) {
        val registration = synchronized(lock) {
            if (desired && userStateGeneration == expectedUserStateGeneration) return
            val previous = activePendingIntent
            desired = true
            userStateGeneration = expectedUserStateGeneration
            registrationRetryAttempt = 0
            registrationRetryJob?.cancel()
            registrationRetryJob = null
            val current = pendingIntent(applicationContext, expectedUserStateGeneration).also {
                activePendingIntent = it
            }
            removalRetryJobs.remove(current)?.cancel()
            removalRetryAttempts.remove(current)
            previous to current
        }
        registration.first?.let(::removeUpdates)
        requestUpdates(registration.second, expectedUserStateGeneration)
    }

    fun stop() {
        val pendingIntent = synchronized(lock) {
            desired = false
            userStateGeneration = null
            registrationRetryAttempt = 0
            registrationRetryJob?.cancel()
            registrationRetryJob = null
            activePendingIntent.also { activePendingIntent = null }
        }
        pendingIntent?.let(::removeUpdates)
    }

    /** Removes a request delivered after the user generation it belonged to was invalidated. */
    fun removeStaleGeneration(staleUserStateGeneration: Long) {
        val isCurrent = synchronized(lock) {
            desired && userStateGeneration == staleUserStateGeneration
        }
        if (!isCurrent) removeUpdates(pendingIntent(applicationContext, staleUserStateGeneration))
    }

    private fun requestUpdates(pendingIntent: PendingIntent, requestGeneration: Long) {
        try {
            requestApproachUpdates(pendingIntent)
                .addOnSuccessListener {
                    val stale = synchronized(lock) {
                        !desired || userStateGeneration != requestGeneration ||
                            activePendingIntent != pendingIntent
                    }
                    if (stale) {
                        removeUpdates(pendingIntent)
                    } else {
                        synchronized(lock) {
                            registrationRetryAttempt = 0
                            registrationRetryJob = null
                        }
                        logger.logPolygonApproachMonitoringStarted()
                    }
                }
                .addOnFailureListener { cause ->
                    retryRegistration(pendingIntent, requestGeneration, cause)
                }
        } catch (e: RuntimeException) {
            retryRegistration(pendingIntent, requestGeneration, e)
        }
    }

    /**
     * The one statement in this class that can throw [SecurityException].
     *
     * The suppression covers exactly that call and nothing else, because the missing-permission case
     * is not ignored: both the synchronous throw and the asynchronous failure route to
     * [retryRegistration], which fails closed on [SecurityException]. Lint cannot see that, so the
     * annotation states it here rather than at the three call sites of [start], which would hide a
     * genuine unchecked permission use somewhere else in the file.
     */
    @SuppressLint("MissingPermission")
    private fun requestApproachUpdates(pendingIntent: PendingIntent): Task<Void> =
        client.requestLocationUpdates(LOCATION_REQUEST, pendingIntent)

    private fun retryRegistration(
        pendingIntent: PendingIntent,
        requestGeneration: Long,
        cause: Throwable
    ) {
        logger.logPolygonApproachMonitoringFailed(cause.message)
        if (cause is SecurityException) {
            synchronized(lock) {
                if (userStateGeneration == requestGeneration && activePendingIntent == pendingIntent) {
                    desired = false
                    userStateGeneration = null
                    activePendingIntent = null
                }
            }
            return
        }
        synchronized(lock) {
            if (!desired || userStateGeneration != requestGeneration ||
                activePendingIntent != pendingIntent
            ) {
                return
            }
            registrationRetryAttempt += 1
            val delayMs = retryDelayMs(registrationRetryAttempt)
            registrationRetryJob?.cancel()
            registrationRetryJob = retryScope.launch {
                delay(delayMs)
                val current = synchronized(lock) {
                    desired && userStateGeneration == requestGeneration &&
                        activePendingIntent == pendingIntent
                }
                if (current) requestUpdates(pendingIntent, requestGeneration)
            }
        }
    }

    private fun removeUpdates(pendingIntent: PendingIntent) {
        try {
            client.removeLocationUpdates(pendingIntent)
                .addOnSuccessListener {
                    val restartGeneration = synchronized(lock) {
                        removalRetryJobs.remove(pendingIntent)?.cancel()
                        removalRetryAttempts.remove(pendingIntent)
                        userStateGeneration?.takeIf {
                            desired && activePendingIntent == pendingIntent
                        }
                    }
                    logger.logPolygonApproachMonitoringStopped()
                    if (restartGeneration != null) {
                        requestUpdates(pendingIntent, restartGeneration)
                    }
                }
                .addOnFailureListener { cause ->
                    retryRemoval(pendingIntent, cause)
                }
        } catch (e: RuntimeException) {
            retryRemoval(pendingIntent, e)
        }
    }

    private fun retryRemoval(pendingIntent: PendingIntent, cause: Throwable) {
        logger.logPolygonApproachMonitoringFailed(cause.message)
        synchronized(lock) {
            if (desired && activePendingIntent == pendingIntent) return
            val attempt = (removalRetryAttempts[pendingIntent] ?: 0) + 1
            removalRetryAttempts[pendingIntent] = attempt
            removalRetryJobs.remove(pendingIntent)?.cancel()
            removalRetryJobs[pendingIntent] = retryScope.launch {
                delay(retryDelayMs(attempt))
                val stillStale = synchronized(lock) {
                    !desired || activePendingIntent != pendingIntent
                }
                if (stillStale) removeUpdates(pendingIntent)
            }
        }
    }

    internal companion object {
        private const val PENDING_INTENT_REQUEST_CODE = 47302
        internal const val EXTRA_USER_STATE_GENERATION =
            "io.customer.geofence.extra.POLYGON_APPROACH_USER_STATE_GENERATION"
        private const val PENDING_INTENT_SCHEME = "customerio-polygon-approach"
        private const val UPDATE_INTERVAL_MS = 150_000L
        private const val FASTEST_UPDATE_INTERVAL_MS = 30_000L
        private const val MINIMUM_DISPLACEMENT_METERS = 100f
        private const val INITIAL_RETRY_MS = 5_000L
        private const val MAXIMUM_RETRY_MS = 300_000L

        val LOCATION_REQUEST: LocationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            UPDATE_INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MINIMUM_DISPLACEMENT_METERS)
            .setWaitForAccurateLocation(false)
            .build()

        internal fun pendingIntent(context: Context, userStateGeneration: Long): PendingIntent {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(context, PolygonApproachReceiver::class.java)
                .setData(Uri.parse("$PENDING_INTENT_SCHEME://$userStateGeneration"))
                .putExtra(EXTRA_USER_STATE_GENERATION, userStateGeneration)
            return PendingIntent.getBroadcast(
                context,
                PENDING_INTENT_REQUEST_CODE,
                intent,
                flags
            )
        }

        private fun retryDelayMs(attempt: Int): Long {
            val exponent = (attempt - 1).coerceIn(0, 6)
            return (INITIAL_RETRY_MS * (1L shl exponent)).coerceAtMost(MAXIMUM_RETRY_MS)
        }
    }
}

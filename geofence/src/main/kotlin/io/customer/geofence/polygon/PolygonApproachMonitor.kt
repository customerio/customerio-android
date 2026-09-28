package io.customer.geofence.polygon

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.net.toUri
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.PolygonApproachStopRefusal
import io.customer.geofence.store.GeofenceRegionStore
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs a bounded location session after a polygon or movement wake cannot reach a safely passive
 * state. Registering polygons never starts it.
 */
internal class PolygonApproachMonitor(
    context: Context,
    private val client: FusedLocationProviderClient,
    private val store: GeofenceRegionStore,
    private val logger: GeofenceLogger,
    backgroundContext: CoroutineContext
) {
    /** Never log while holding this: the host log dispatcher is customer code. */
    private val lock = Any()
    private var desired = false
    private var userStateGeneration: Long? = null
    private var sessionDeadlineElapsedRealtimeMs: Long? = null
    private var activePendingIntent: PendingIntent? = null
    private val applicationContext = context.applicationContext
    private val retryScope = CoroutineScope(SupervisorJob() + backgroundContext)
    private var registrationRetryAttempt = 0
    private var registrationRetryJob: Job? = null
    private var sessionTimeoutJob: Job? = null
    private val removalRetryAttempts = mutableMapOf<PendingIntent, Int>()
    private val removalRetryJobs = mutableMapOf<PendingIntent, Job>()

    /**
     * Generations whose registration's ending was already reported, so a repeat removal is not
     * reported again. Never evicted: GMS reports success for a stale stop at any later time.
     */
    private val reportedEndingGenerations = mutableSetOf<Long>()

    fun start(
        expectedUserStateGeneration: Long,
        sessionDeadlineElapsedRealtimeMs: Long = newSessionDeadlineElapsedRealtimeMs()
    ) {
        if (sessionDeadlineElapsedRealtimeMs <= SystemClock.elapsedRealtime()) {
            stop(expectedUserStateGeneration, sessionDeadlineElapsedRealtimeMs)
            return
        }
        val registration = synchronized(lock) {
            // Checked against the store, since the caller's generation may be stale. Under this
            // lock, a sign-out either bumps the generation first or removes the request armed here.
            if (expectedUserStateGeneration != store.userStateGeneration()) {
                return
            }
            if (
                desired &&
                userStateGeneration == expectedUserStateGeneration &&
                this.sessionDeadlineElapsedRealtimeMs.orExpired() > SystemClock.elapsedRealtime()
            ) {
                return
            }
            // Returned from this block, not kept on the instance: a concurrent start() would
            // overwrite it.
            val previous = activePendingIntent
            val outgoingDeadline = this.sessionDeadlineElapsedRealtimeMs
            // The replaced request's generation, not the arming one, so its ending is recorded
            // correctly.
            val outgoingGeneration = this.userStateGeneration
            desired = true
            userStateGeneration = expectedUserStateGeneration
            this.sessionDeadlineElapsedRealtimeMs = sessionDeadlineElapsedRealtimeMs
            registrationRetryAttempt = 0
            registrationRetryJob?.cancel()
            registrationRetryJob = null
            sessionTimeoutJob?.cancel()
            sessionTimeoutJob = retryScope.launch {
                delay(
                    (sessionDeadlineElapsedRealtimeMs - SystemClock.elapsedRealtime())
                        .coerceAtLeast(0L)
                )
                stop(expectedUserStateGeneration, sessionDeadlineElapsedRealtimeMs)
            }
            val current = pendingIntent(
                applicationContext,
                expectedUserStateGeneration,
                sessionDeadlineElapsedRealtimeMs
            ).also {
                activePendingIntent = it
            }
            removalRetryJobs.remove(current)?.cancel()
            removalRetryAttempts.remove(current)
            // Seeded so a session that receives nothing reports n=0. An existing entry holds a batch
            // that cold-started the process before this adopt, so it is claimed, not replaced.
            val existingCount = samplesByDeadline[sessionDeadlineElapsedRealtimeMs]
            if (existingCount == null) {
                samplesByDeadline[sessionDeadlineElapsedRealtimeMs] = SessionSamples(armedHere = true)
            } else {
                existingCount.armedHere = true
            }
            // An equal PendingIntent ends the outgoing session without a removal, so its ending is
            // reported here and the generation claimed against a second report.
            val endingToReport = outgoingDeadline
                ?.takeIf { previous != null && previous == current && it != sessionDeadlineElapsedRealtimeMs }
                ?.also { reportedEndingGenerations += expectedUserStateGeneration }
            Rearm(previous, current, outgoingDeadline, outgoingGeneration, endingToReport)
        }
        // Not for an equal intent: removing it would cancel the request made below.
        registration.previous?.takeIf { it != registration.current }?.let {
            removeUpdates(it, registration.outgoingDeadline, registration.outgoingGeneration)
        }
        registration.endingToReport?.let { outgoing ->
            logger.logPolygonApproachMonitoringStopped(
                samplesReceived = consumeSessionCount(outgoing)?.samplesReceived
            )
        }
        requestUpdates(registration.current, expectedUserStateGeneration, sessionDeadlineElapsedRealtimeMs)
    }

    /**
     * Batch counts per session deadline, the only value that identifies a session: a generation's
     * sessions share one intent, and one shared counter would be reset by the next session's arm
     * before the previous removal completes.
     */
    private val samplesByDeadline =
        java.util.concurrent.ConcurrentHashMap<Long, SessionSamples>()

    /**
     * [armedHere] marks a session this process armed, where n=0 is evidence. Settable because a
     * batch can create the entry before the arm claims it.
     */
    private class SessionSamples(var armedHere: Boolean) {
        val samples = java.util.concurrent.atomic.AtomicInteger(0)
    }

    /** Removes the entry, so the map holds only unreported sessions and the age prune stays safe. */
    private fun consumeSessionCount(deadline: Long): ClaimedCount? {
        val entry = samplesByDeadline.remove(deadline) ?: return null
        val samples = entry.samples.get()
        return ClaimedCount(
            // An unarmed zero says nothing about the session, so it is reported as unknown.
            samplesReceived = samples.takeIf { entry.armedHere || it > 0 },
            armedHere = entry.armedHere
        )
    }

    private class ClaimedCount(val samplesReceived: Int?, val armedHere: Boolean)

    /** Records an ending as accounted for without reporting one, for a session that never began. */
    private fun suppressEnding(deadline: Long, registrationGeneration: Long) {
        samplesByDeadline.remove(deadline)
        synchronized(lock) { reportedEndingGenerations += registrationGeneration }
    }

    /**
     * By age, not size: evicting by size could drop a live session's entry while its removal is in
     * flight, so its teardown would report nothing for a session that did deliver.
     */
    private fun pruneAbandonedSessionCounts() {
        if (samplesByDeadline.size <= MAX_TRACKED_SESSIONS) return
        val abandonedBefore = SystemClock.elapsedRealtime() - ABANDONED_SESSION_COUNT_AGE_MS
        samplesByDeadline.keys.filter { it < abandonedBefore }.forEach(samplesByDeadline::remove)
    }

    /**
     * Creates the entry when missing: a PendingIntent can cold-start the process, and its batch is
     * recorded before the session is adopted. A late batch for a reported session creates an
     * unarmed entry, which [removeUpdates] does not report again.
     */
    fun recordSampleDelivered(sessionDeadlineElapsedRealtimeMs: Long) {
        // putIfAbsent rather than merge or compute: those are API 24 and minSdk here is 21.
        val entry = samplesByDeadline.putIfAbsent(
            sessionDeadlineElapsedRealtimeMs,
            SessionSamples(armedHere = false)
        ) ?: samplesByDeadline[sessionDeadlineElapsedRealtimeMs]
        entry?.samples?.incrementAndGet()
        pruneAbandonedSessionCounts()
    }

    fun stop(
        expectedUserStateGeneration: Long? = null,
        expectedSessionDeadlineElapsedRealtimeMs: Long? = null
    ) {
        var refusal: PolygonApproachStopRefusal? = null
        var deadlineBeingTornDown: Long? = null
        var generationBeingTornDown: Long? = null
        val pendingIntent = synchronized(lock) {
            // A named generation stops only its own session; a bare stop() stops whatever is live.
            if (
                desired &&
                expectedUserStateGeneration != null &&
                userStateGeneration != expectedUserStateGeneration
            ) {
                refusal = PolygonApproachStopRefusal.NOT_CURRENT_SESSION
                return@synchronized null
            }
            if (
                expectedSessionDeadlineElapsedRealtimeMs != null &&
                desired &&
                sessionDeadlineElapsedRealtimeMs != expectedSessionDeadlineElapsedRealtimeMs
            ) {
                refusal = PolygonApproachStopRefusal.DEADLINE_MISMATCH
                return@synchronized null
            }
            val generationToRemove = userStateGeneration ?: expectedUserStateGeneration
            generationBeingTornDown = generationToRemove
            deadlineBeingTornDown = sessionDeadlineElapsedRealtimeMs
                ?: expectedSessionDeadlineElapsedRealtimeMs
            desired = false
            userStateGeneration = null
            sessionDeadlineElapsedRealtimeMs = null
            registrationRetryAttempt = 0
            registrationRetryJob?.cancel()
            registrationRetryJob = null
            sessionTimeoutJob?.cancel()
            sessionTimeoutJob = null
            activePendingIntent.also { activePendingIntent = null }
                ?: generationToRemove?.let { existingPendingIntentOrNull(applicationContext, it) }
        }
        refusal?.let {
            logger.logPolygonApproachStopRefused(it)
            return
        }
        pendingIntent?.let { removeUpdates(it, deadlineBeingTornDown, generationBeingTornDown) }
    }

    private fun Long?.orExpired(): Long = this ?: Long.MIN_VALUE

    /** Removes a request delivered after the user generation it belonged to was invalidated. */
    fun removeStaleGeneration(staleUserStateGeneration: Long) {
        val isCurrent = synchronized(lock) {
            desired && userStateGeneration == staleUserStateGeneration
        }
        if (!isCurrent) {
            existingPendingIntentOrNull(applicationContext, staleUserStateGeneration)
                ?.let { removeUpdates(it, namedDeadline = null, registrationGeneration = staleUserStateGeneration) }
        }
    }

    private fun requestUpdates(
        pendingIntent: PendingIntent,
        requestGeneration: Long,
        sessionDeadlineElapsedRealtimeMs: Long?
    ) {
        try {
            requestApproachUpdates(pendingIntent)
                .addOnSuccessListener {
                    val stale = synchronized(lock) {
                        !desired || userStateGeneration != requestGeneration ||
                            activePendingIntent != pendingIntent
                    }
                    if (stale) {
                        logger.logPolygonApproachRequestDiscarded(
                            synchronized(lock) { userStateGeneration }
                        )
                        removeUpdates(pendingIntent, sessionDeadlineElapsedRealtimeMs, requestGeneration)
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
     * Suppressed here, not at [start]'s callers where it would hide a genuine unchecked use. A
     * [SecurityException], thrown or async, reaches [retryRegistration], which fails closed on it.
     */
    @SuppressLint("MissingPermission")
    private fun requestApproachUpdates(pendingIntent: PendingIntent): Task<Void> {
        // Read at request time: a retry or restart continues the session, asking only for what is left.
        val deadline = synchronized(lock) { sessionDeadlineElapsedRealtimeMs }
        val remaining = deadline?.minus(SystemClock.elapsedRealtime())?.coerceAtLeast(0L)
            ?: MAXIMUM_SESSION_DURATION_MS
        return client.requestLocationUpdates(locationRequest(remaining), pendingIntent)
    }

    private fun retryRegistration(
        pendingIntent: PendingIntent,
        requestGeneration: Long,
        cause: Throwable
    ) {
        logger.logPolygonApproachRequestFailed(cause.message, operation = "request_updates")
        if (cause is SecurityException) {
            var abandonedDeadline: Long? = null
            synchronized(lock) {
                if (userStateGeneration == requestGeneration && activePendingIntent == pendingIntent) {
                    desired = false
                    userStateGeneration = null
                    activePendingIntent = null
                    abandonedDeadline = sessionDeadlineElapsedRealtimeMs
                    sessionDeadlineElapsedRealtimeMs = null
                    sessionTimeoutJob?.cancel()
                    sessionTimeoutJob = null
                }
            }
            // The PendingIntent already exists, so a later stop finds it and GMS reports success;
            // this keeps that removal from reporting an ending for a request never attached.
            abandonedDeadline?.let { suppressEnding(it, requestGeneration) }
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
                if (current) {
                    requestUpdates(
                        pendingIntent,
                        requestGeneration,
                        synchronized(lock) { sessionDeadlineElapsedRealtimeMs }
                    )
                }
            }
        }
    }

    /**
     * What one [start] finishes outside the lock. Returned rather than stored on the instance, so
     * a concurrent [start] cannot overwrite it first.
     */
    private data class Rearm(
        val previous: PendingIntent?,
        val current: PendingIntent,
        val outgoingDeadline: Long?,
        val outgoingGeneration: Long?,
        val endingToReport: Long?
    )

    /**
     * [namedDeadline] is the session this removal is for, which may be older than the live one
     * sharing its registration. Named so it does not shadow the deadline field.
     */
    private fun removeUpdates(
        pendingIntent: PendingIntent,
        namedDeadline: Long? = null,
        registrationGeneration: Long? = null
    ) {
        try {
            client.removeLocationUpdates(pendingIntent)
                .addOnSuccessListener {
                    var liveDeadline: Long? = null
                    var registrationAlreadyReported = false
                    val restartGeneration = synchronized(lock) {
                        removalRetryJobs.remove(pendingIntent)?.cancel()
                        removalRetryAttempts.remove(pendingIntent)
                        liveDeadline = sessionDeadlineElapsedRealtimeMs
                        registrationAlreadyReported = registrationGeneration != null &&
                            registrationGeneration in reportedEndingGenerations
                        userStateGeneration?.takeIf {
                            desired && activePendingIntent == pendingIntent
                        }
                    }
                    // A removal naming the live deadline ended nothing, as the request is re-issued
                    // below; one naming an older deadline ended that session.
                    val endedSession = when {
                        namedDeadline != null ->
                            namedDeadline != liveDeadline
                        else -> restartGeneration == null
                    }
                    if (endedSession) {
                        val claimed = namedDeadline?.let(::consumeSessionCount)
                        val reportedAnEnding = when {
                            // Armed here: each session sharing a registration reports its own ending.
                            claimed != null && claimed.armedHere -> {
                                logger.logPolygonApproachMonitoringStopped(claimed.samplesReceived)
                                true
                            }
                            !registrationAlreadyReported -> {
                                logger.logPolygonApproachMonitoringStopped(claimed?.samplesReceived)
                                true
                            }
                            else -> false
                        }
                        if (reportedAnEnding && registrationGeneration != null) {
                            synchronized(lock) { reportedEndingGenerations += registrationGeneration }
                        }
                    }
                    if (restartGeneration != null) {
                        requestUpdates(
                            pendingIntent,
                            restartGeneration,
                            synchronized(lock) { sessionDeadlineElapsedRealtimeMs }
                        )
                    }
                }
                .addOnFailureListener { cause ->
                    retryRemoval(pendingIntent, cause, namedDeadline, registrationGeneration)
                }
        } catch (e: RuntimeException) {
            retryRemoval(pendingIntent, e, namedDeadline, registrationGeneration)
        }
    }

    /** Forwards both keys, so the retried removal can still tell a repeat teardown from a first. */
    private fun retryRemoval(
        pendingIntent: PendingIntent,
        cause: Throwable,
        namedDeadline: Long?,
        registrationGeneration: Long?
    ) {
        logger.logPolygonApproachRequestFailed(cause.message, operation = "remove_updates")
        synchronized(lock) {
            if (desired && activePendingIntent == pendingIntent) {
                // Wanted again: drop its retry state, or the maps keep an entry per generation.
                removalRetryAttempts.remove(pendingIntent)
                removalRetryJobs.remove(pendingIntent)?.cancel()
                return
            }
            val attempt = (removalRetryAttempts[pendingIntent] ?: 0) + 1
            removalRetryAttempts[pendingIntent] = attempt
            removalRetryJobs.remove(pendingIntent)?.cancel()
            removalRetryJobs[pendingIntent] = retryScope.launch {
                delay(retryDelayMs(attempt))
                val stillStale = synchronized(lock) {
                    !desired || activePendingIntent != pendingIntent
                }
                if (stillStale) removeUpdates(pendingIntent, namedDeadline, registrationGeneration)
            }
        }
    }

    internal companion object {
        /** Sessions whose sample counts are retained before pruning is considered. */
        private const val MAX_TRACKED_SESSIONS = 16

        /**
         * How far past its deadline a count is kept: beyond any removal retry, so pruning cannot
         * reach a session whose teardown is still pending.
         */
        private const val ABANDONED_SESSION_COUNT_AGE_MS = 10 * 60_000L

        private const val PENDING_INTENT_REQUEST_CODE = 47302
        internal const val EXTRA_USER_STATE_GENERATION =
            "io.customer.geofence.extra.POLYGON_APPROACH_USER_STATE_GENERATION"
        private const val PENDING_INTENT_SCHEME = "customerio-polygon-approach"
        internal const val EXTRA_SESSION_DEADLINE_ELAPSED_REALTIME_MS =
            "io.customer.geofence.extra.POLYGON_APPROACH_SESSION_DEADLINE_ELAPSED_REALTIME_MS"
        private const val UPDATE_INTERVAL_MS = 15_000L
        private const val FASTEST_UPDATE_INTERVAL_MS = 5_000L
        private const val MAXIMUM_SESSION_DURATION_MS = 2 * 60_000L
        private const val INITIAL_RETRY_MS = 5_000L
        private const val MAXIMUM_RETRY_MS = 300_000L

        /**
         * The budget goes on the request too: our timer dies with the process but the registration
         * outlives it, and a stationary device gets too few deliveries to notice the deadline.
         */
        internal fun locationRequest(durationMs: Long): LocationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            UPDATE_INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL_MS)
            // No displacement filter: it would sample an arrived, stopped device only once.
            .setWaitForAccurateLocation(false)
            .setDurationMillis(durationMs)
            .build()

        internal fun newSessionDeadlineElapsedRealtimeMs(): Long =
            SystemClock.elapsedRealtime() + MAXIMUM_SESSION_DURATION_MS

        /**
         * For removal paths: [pendingIntent] would mint one for a generation never registered.
         * Still finds a registration from an earlier process, which cold-process teardown needs.
         */
        internal fun existingPendingIntentOrNull(
            context: Context,
            userStateGeneration: Long
        ): PendingIntent? {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_NO_CREATE
            }
            return PendingIntent.getBroadcast(
                context,
                PENDING_INTENT_REQUEST_CODE,
                Intent(context, PolygonApproachReceiver::class.java)
                    .setData("$PENDING_INTENT_SCHEME://$userStateGeneration".toUri()),
                flags
            )
        }

        /**
         * Identity is the generation in the URI; the deadline is an extra, so a generation's
         * sessions share one intent. Keep the deadline out of the URI: a cold process rebuilds the
         * intent from the generation to stop it, and the PendingIntent-keyed maps need it equal.
         */
        internal fun pendingIntent(
            context: Context,
            userStateGeneration: Long,
            sessionDeadlineElapsedRealtimeMs: Long = 0L
        ): PendingIntent {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(context, PolygonApproachReceiver::class.java)
                .setData("$PENDING_INTENT_SCHEME://$userStateGeneration".toUri())
                .putExtra(EXTRA_USER_STATE_GENERATION, userStateGeneration)
                .putExtra(
                    EXTRA_SESSION_DEADLINE_ELAPSED_REALTIME_MS,
                    sessionDeadlineElapsedRealtimeMs
                )
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

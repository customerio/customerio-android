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
 * Runs a bounded location session after an OS polygon or shared-movement wake cannot establish a
 * safely passive state. Merely registering polygons never starts this request. Exact polygon
 * decisions remain in [PolygonLocationEngine].
 */
internal class PolygonApproachMonitor(
    context: Context,
    private val client: FusedLocationProviderClient,
    private val store: GeofenceRegionStore,
    private val logger: GeofenceLogger,
    backgroundContext: CoroutineContext
) {
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
     * Generations whose registration already had its ending reported, so a later removal of the
     * same registration is not reported again.
     *
     * Keyed by generation because that identifies a registration: the deadline is an extra, which
     * `Intent.filterEquals` ignores, so every session in a generation shares one GMS registration.
     * Never evicted, because nothing cancels the PendingIntent and a stale stop can succeed at any
     * later time; growth is bounded by identify and sign-out. A new process starts empty, so it may
     * report one inherited ending again.
     */
    private val reportedEndingGenerations = mutableSetOf<Long>()

    /**
     * Asks Play services for approach fixes, best-effort.
     *
     * Not `@RequiresPermission`: permission can be revoked between any check and this call, so a
     * missing permission is a handled outcome instead. The request fails with [SecurityException],
     * and [retryRegistration] logs it and clears `desired` so nothing is retried.
     */
    fun start(
        expectedUserStateGeneration: Long,
        sessionDeadlineElapsedRealtimeMs: Long = newSessionDeadlineElapsedRealtimeMs()
    ) {
        if (sessionDeadlineElapsedRealtimeMs <= SystemClock.elapsedRealtime()) {
            stop(expectedUserStateGeneration, sessionDeadlineElapsedRealtimeMs)
            return
        }
        val registration = synchronized(lock) {
            // Checked against the store: a caller can resume holding a generation that an identify
            // has since replaced or a sign-out has ended, and this monitor's own state shows
            // neither. Under this lock, the sign-out teardown either bumps the generation first or
            // removes the request armed here.
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
            // Returned from this block rather than stored on the instance: a concurrent start()
            // would overwrite it before this caller reached removeUpdates.
            val previous = activePendingIntent
            val outgoingDeadline = this.sessionDeadlineElapsedRealtimeMs
            // The generation of the request being replaced, not the one arming, so its ending is
            // recorded against the right registration.
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
            // Seeded at zero, not left absent: a session armed here that receives nothing reports
            // n=0. An existing entry holds a batch that cold-started this process before the
            // session was adopted, so it is kept and claimed rather than replaced.
            val existingCount = samplesByDeadline[sessionDeadlineElapsedRealtimeMs]
            if (existingCount == null) {
                samplesByDeadline[sessionDeadlineElapsedRealtimeMs] = SessionSamples(armedHere = true)
            } else {
                existingCount.armedHere = true
            }
            // An equal PendingIntent ends the outgoing session without a removal. This call reports
            // that ending and claims the generation, so a later stop naming the outgoing deadline
            // is not reported as a first ending.
            val endingToReport = outgoingDeadline
                ?.takeIf { previous != null && previous == current && it != sessionDeadlineElapsedRealtimeMs }
                ?.also { reportedEndingGenerations += expectedUserStateGeneration }
            Rearm(previous, current, outgoingDeadline, outgoingGeneration, endingToReport)
        }
        // Only for a different request: a re-arm for the same generation builds an equal
        // PendingIntent, and removing it would cancel the request made just below.
        registration.previous?.takeIf { it != registration.current }?.let {
            removeUpdates(it, registration.outgoingDeadline, registration.outgoingGeneration)
        }
        // Outside the lock: the host's log dispatcher is customer code.
        registration.endingToReport?.let { outgoing ->
            logger.logPolygonApproachMonitoringStopped(
                samplesReceived = consumeSessionCount(outgoing)?.samplesReceived
            )
        }
        requestUpdates(registration.current, expectedUserStateGeneration, sessionDeadlineElapsedRealtimeMs)
    }

    /**
     * Sample batches counted per session deadline, the only value that identifies a session.
     *
     * Not per [PendingIntent]: sessions in one generation share an equal intent (the deadline is an
     * extra), and same-generation overlap is the common case. Not one shared counter: the next
     * session arming would reset it before the previous one's removal completes. The receiver reads
     * the deadline from the delivering intent, so each batch names its own session.
     */
    private val samplesByDeadline =
        java.util.concurrent.ConcurrentHashMap<Long, SessionSamples>()

    /**
     * One session's batch count, held until its ending is reported.
     *
     * [armedHere] marks a session this process armed, for which n=0 is evidence rather than a
     * guess. Settable because a batch can create the entry before the arm claims it.
     */
    private class SessionSamples(var armedHere: Boolean) {
        val samples = java.util.concurrent.atomic.AtomicInteger(0)
    }

    /**
     * Takes the count for the session named by [deadline], if this process has one. Removing it
     * keeps the map bounded by unreported sessions, which is what makes the age-based prune safe.
     */
    private fun consumeSessionCount(deadline: Long): ClaimedCount? {
        val entry = samplesByDeadline.remove(deadline) ?: return null
        val samples = entry.samples.get()
        return ClaimedCount(
            // An unarmed zero would claim n=0 for a session nothing is known about, so it is
            // reported as unknown. Unreachable today: the batch that creates an unarmed entry
            // also increments it.
            samplesReceived = samples.takeIf { entry.armedHere || it > 0 },
            armedHere = entry.armedHere
        )
    }

    /**
     * A count taken from an entry, with whether this process armed its session. An unarmed entry
     * can be created by a batch arriving after that session's ending was already reported, so
     * reporting it could record one ending twice.
     */
    private class ClaimedCount(val samplesReceived: Int?, val armedHere: Boolean)

    /** Records an ending as accounted for without reporting one, for a session that never began. */
    private fun suppressEnding(deadline: Long, registrationGeneration: Long) {
        samplesByDeadline.remove(deadline)
        synchronized(lock) { reportedEndingGenerations += registrationGeneration }
    }

    /**
     * Drops counts for sessions that can no longer be torn down.
     *
     * Evicting by size would delete a live session's entry while its removal callback is still in
     * flight, and the teardown would then read absent and report nothing for a session that did
     * deliver. A session deadline is at most [MAXIMUM_SESSION_DURATION_MS] ahead of its start, so
     * anything this far in the past belongs to no session that can still report.
     */
    private fun pruneAbandonedSessionCounts() {
        if (samplesByDeadline.size <= MAX_TRACKED_SESSIONS) return
        val abandonedBefore = SystemClock.elapsedRealtime() - ABANDONED_SESSION_COUNT_AGE_MS
        samplesByDeadline.keys.filter { it < abandonedBefore }.forEach(samplesByDeadline::remove)
    }

    /**
     * Called by the controller for each delivered batch, naming the session that delivered it.
     *
     * Creates the entry when missing, because a PendingIntent can cold-start the process and the
     * receiver records that batch before it adopts the session. A late batch for a session already
     * reported creates an unarmed entry, which [removeUpdates] does not report a second time.
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
            // A caller naming a generation stops only its own session: an identify can land between
            // its read of the generation and here. A bare stop() stops whatever is live.
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
        // Logged outside the lock: the host log dispatcher is customer code. The refusal is
        // checked first because a refusal and an absent pending intent both yield null above.
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
                        // The request just made is already stale. The removal below reports the
                        // ending; this records why.
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
     * The one call in this class that can throw [SecurityException]. Suppressed here only: both
     * the synchronous throw and the async failure route to [retryRegistration], which fails closed
     * on it, and suppressing at the callers of [start] would hide a genuine unchecked use.
     */
    @SuppressLint("MissingPermission")
    private fun requestApproachUpdates(pendingIntent: PendingIntent): Task<Void> {
        // Read at request time, not at start: a retry or a post-removal restart is the same session
        // continuing, so it asks the OS for what is left rather than renewing the bound.
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
                    // Nothing is registered now, so leaving this armed would later "stop" a request
                    // that never existed and log a removal that did not happen.
                    abandonedDeadline = sessionDeadlineElapsedRealtimeMs
                    sessionDeadlineElapsedRealtimeMs = null
                    sessionTimeoutJob?.cancel()
                    sessionTimeoutJob = null
                }
            }
            // The PendingIntent was minted before the failure, so a later stop still finds it and
            // GMS reports success for a request never attached. Marking the ending as accounted
            // for keeps that removal from reporting one.
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
     * What one [start] call has to finish outside the lock: the request it replaced, the one it
     * armed, and the ending it has taken responsibility for reporting.
     */
    private data class Rearm(
        val previous: PendingIntent?,
        val current: PendingIntent,
        val outgoingDeadline: Long?,
        val outgoingGeneration: Long?,
        val endingToReport: Long?
    )

    /**
     * [namedDeadline] is the session this removal is for, which is not always the live one: equal
     * intents share a registration, so a removal can name an older session while a newer one runs.
     * Named so it does not shadow the `sessionDeadlineElapsedRealtimeMs` field.
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
                    // GMS reports success whether or not a request is attached, and nothing cancels
                    // the PendingIntent, so a stop naming an already-removed generation succeeds
                    // again; [reportedEndingGenerations] keeps that from reading as another ending.
                    //
                    // The ending belongs to the session this removal names. One naming the live
                    // deadline ended nothing (the request is re-issued below); one naming an older
                    // deadline ended that session though the registration continues. A first
                    // removal with no count entry is a real ending this process cannot count, so
                    // it is reported without one.
                    val endedSession = when {
                        namedDeadline != null ->
                            namedDeadline != liveDeadline
                        else -> restartGeneration == null
                    }
                    if (endedSession) {
                        val claimed = namedDeadline?.let(::consumeSessionCount)
                        val reportedAnEnding = when {
                            // Armed here: reported on its own count, so sessions sharing one
                            // registration each report their own ending.
                            claimed != null && claimed.armedHere -> {
                                logger.logPolygonApproachMonitoringStopped(claimed.samplesReceived)
                                true
                            }
                            // Unarmed or no entry: not attributable to a session opened here, so
                            // reported only if the registration's ending is not already on record.
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

    /**
     * Carries both keys the retried removal needs, so its success path can still tell a repeat
     * teardown from a first one.
     */
    private fun retryRemoval(
        pendingIntent: PendingIntent,
        cause: Throwable,
        namedDeadline: Long?,
        registrationGeneration: Long?
    ) {
        logger.logPolygonApproachRequestFailed(cause.message, operation = "remove_updates")
        synchronized(lock) {
            if (desired && activePendingIntent == pendingIntent) {
                // Wanted again, so drop its retry state; these maps would otherwise keep one entry
                // per generation's PendingIntent.
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
         * How far past its deadline a session's count is kept. Well beyond
         * [MAXIMUM_SESSION_DURATION_MS] plus any removal retry, so pruning cannot reach a session
         * whose teardown has not happened yet.
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
         * The session budget goes on the request itself, not only on our timer.
         *
         * [sessionTimeoutJob] and the deadline extra both die with the process, while a
         * `PendingIntent` registration outlives it. Deliveries to a stationary device are too
         * sparse to rely on for noticing the deadline has passed, so without this the request can
         * stay live indefinitely.
         */
        internal fun locationRequest(durationMs: Long): LocationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            UPDATE_INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL_MS)
            // No displacement filter. The case this session exists to judge is someone who has
            // arrived and stopped, and the filter drops consecutive fixes within the gate: such a
            // device is sampled once and then not again for the rest of the session.
            .setWaitForAccurateLocation(false)
            .setDurationMillis(durationMs)
            .build()

        internal fun newSessionDeadlineElapsedRealtimeMs(): Long =
            SystemClock.elapsedRealtime() + MAXIMUM_SESSION_DURATION_MS

        /**
         * The registration for [userStateGeneration] if one exists, and null otherwise.
         *
         * Removal paths ask for this rather than [pendingIntent]: `FLAG_UPDATE_CURRENT` CREATES a
         * `PendingIntent` when none is present, so removing a generation that was never registered
         * would mint one and leave it behind. A registration made before this process started is
         * still found, which is what the cold-process teardown depends on.
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
         * Identity is the generation in the data URI only: `PendingIntent` equality ignores extras,
         * so a different deadline is the same intent and `FLAG_UPDATE_CURRENT` rewrites its extras.
         * Keep the deadline out of the URI: a cold process stops a session by rebuilding the
         * intent for its generation, and the `PendingIntent`-keyed maps rely on it staying equal.
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

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
     * The request most recently removed, so a repeat removal of it is not reported as a teardown.
     * One slot rather than a set: the case this exists for is the same generation being stopped
     * over and over with nothing armed in between, and a single slot needs no pruning.
     */
    private var lastRemovedPendingIntent: PendingIntent? = null

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
    fun start(
        expectedUserStateGeneration: Long,
        sessionDeadlineElapsedRealtimeMs: Long = newSessionDeadlineElapsedRealtimeMs()
    ) {
        if (sessionDeadlineElapsedRealtimeMs <= SystemClock.elapsedRealtime()) {
            stop(expectedUserStateGeneration, sessionDeadlineElapsedRealtimeMs)
            return
        }
        val registration = synchronized(lock) {
            // The store owns which generation is live, and `start` is reached with no lock held
            // across the decision that led here: a caller preempted after deciding to sample
            // resumes holding the generation it captured before suspending. By then an identify
            // can have armed a newer session, which arming the older one would replace, or a
            // sign-out can have ended that generation outright, which would open a fine-location
            // session for a user who is no longer signed in. Neither is visible from this
            // monitor's own state, because `stop` clears the live generation and sign-out arms
            // nothing newer to compare against.
            //
            // Read under this lock so the teardown that follows sign-out either bumps the
            // generation before this check, or names the request armed here and removes it.
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
            // Returned from this block rather than stashed on the instance: a concurrent start()
            // would overwrite instance state before the first caller reached removeUpdates, and
            // the outgoing session would then consume another session's counter.
            val previous = activePendingIntent
            val outgoingDeadline = this.sessionDeadlineElapsedRealtimeMs
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
            // Zero, not absent: a session that armed has observed evidence of nothing delivered,
            // and that is the finding. Absent means the count could not be attributed at all.
            samplesByDeadline.putIfAbsent(
                sessionDeadlineElapsedRealtimeMs,
                java.util.concurrent.atomic.AtomicInteger(0)
            )
            // Something is armed again, so the next removal can genuinely tear it down and is
            // worth a record. Only reached when this call actually arms: the early returns above
            // leave the memo alone.
            lastRemovedPendingIntent = null
            Triple(previous, current, outgoingDeadline)
        }
        // Only when it is a DIFFERENT request. A re-arm for the same generation builds an equal
        // PendingIntent, so removing "the previous one" would cancel the request made just below,
        // and the removal's success listener would re-request it and log a teardown that never
        // happened.
        registration.first?.takeIf { it != registration.second }?.let {
            removeUpdates(it, registration.third)
        }
        // The equal-PendingIntent case still ends a session, it just does not remove anything. Its
        // count is consumed here because the removal that normally reports it is skipped above, so
        // without this the outgoing deadline's tally is never reported and never freed.
        registration.first
            ?.takeIf { it == registration.second }
            ?.let { registration.third }
            ?.takeIf { it != sessionDeadlineElapsedRealtimeMs }
            ?.let { outgoingDeadline ->
                logger.logPolygonApproachMonitoringStopped(
                    samplesReceived = samplesByDeadline.remove(outgoingDeadline)?.get()
                )
            }
        requestUpdates(registration.second, expectedUserStateGeneration, sessionDeadlineElapsedRealtimeMs)
    }

    /**
     * Sample batches counted per session deadline, which is the only value that identifies a
     * session.
     *
     * Not per [PendingIntent]: `setData` carries the generation but the deadline is an extra, and
     * `Intent.filterEquals` ignores extras under a fixed request code, so two sessions in one
     * generation build an EQUAL PendingIntent. A generation changes only on identify or sign-out
     * while the sampler arms and tears down on every callback, so same-generation overlap is the
     * common case and keying on the intent would leave the false negative in place exactly there.
     *
     * Not per monitor either: a shared counter is reset by the next session arming before the
     * previous one's removal completes.
     *
     * The batch itself knows which session it belongs to, because the receiver reads the deadline
     * from the delivering intent. Nothing here infers the owner from what happens to be active.
     */
    private val samplesByDeadline =
        java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger>()

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

    /** Called by the controller for each delivered batch, naming the session that delivered it. */
    fun recordSampleDelivered(sessionDeadlineElapsedRealtimeMs: Long) {
        // putIfAbsent rather than merge or compute: those are API 24 and minSdk here is 21.
        samplesByDeadline
            .putIfAbsent(sessionDeadlineElapsedRealtimeMs, java.util.concurrent.atomic.AtomicInteger(1))
            ?.incrementAndGet()
        pruneAbandonedSessionCounts()
    }

    fun stop(
        expectedUserStateGeneration: Long? = null,
        expectedSessionDeadlineElapsedRealtimeMs: Long? = null
    ) {
        var refusal: PolygonApproachStopRefusal? = null
        var deadlineBeingTornDown: Long? = null
        val pendingIntent = synchronized(lock) {
            // A caller naming a generation means "stop the session I started", so a live session
            // from a later one is not theirs to tear down: an identify can land between reading the
            // generation and getting here, and this runs with no lock held across that gap. A bare
            // stop() names nothing and still stops whatever is live.
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
        // Logged outside the lock. The dispatcher set by setLogDispatcher is host-supplied code,
        // so invoking it while holding this lock would let a slow customer lambda stall every
        // start and stop on the monitor. The rest of this file already logs after its blocks close.
        //
        // Order is load-bearing: a refusal and a legitimately absent pending intent both yield
        // null above, and they are told apart only by checking the refusal first.
        refusal?.let {
            logger.logPolygonApproachStopRefused(it)
            return
        }
        pendingIntent?.let { removeUpdates(it, deadlineBeingTornDown) }
    }

    private fun Long?.orExpired(): Long = this ?: Long.MIN_VALUE

    /** Removes a request delivered after the user generation it belonged to was invalidated. */
    fun removeStaleGeneration(staleUserStateGeneration: Long) {
        val isCurrent = synchronized(lock) {
            desired && userStateGeneration == staleUserStateGeneration
        }
        if (!isCurrent) {
            existingPendingIntentOrNull(applicationContext, staleUserStateGeneration)
                ?.let(::removeUpdates)
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
                        // Armed, then discarded the request we had just made. This is the shape
                        // that produces a started session which never delivers a sample. The
                        // removal below emits the ending; this only records why it was triggered.
                        logger.logPolygonApproachRequestDiscarded(
                            synchronized(lock) { userStateGeneration }
                        )
                        removeUpdates(pendingIntent, sessionDeadlineElapsedRealtimeMs)
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
            synchronized(lock) {
                if (userStateGeneration == requestGeneration && activePendingIntent == pendingIntent) {
                    desired = false
                    userStateGeneration = null
                    activePendingIntent = null
                    // Nothing is registered now, so leaving this armed would later "stop" a request
                    // that never existed and log a removal that did not happen.
                    sessionDeadlineElapsedRealtimeMs = null
                    sessionTimeoutJob?.cancel()
                    sessionTimeoutJob = null
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

    private fun removeUpdates(
        pendingIntent: PendingIntent,
        sessionDeadlineElapsedRealtimeMs: Long? = null
    ) {
        try {
            client.removeLocationUpdates(pendingIntent)
                .addOnSuccessListener {
                    var removedALiveRequest = false
                    val restartGeneration = synchronized(lock) {
                        removalRetryJobs.remove(pendingIntent)?.cancel()
                        removalRetryAttempts.remove(pendingIntent)
                        // GMS reports success whether or not a request is attached, and nothing
                        // cancels the PendingIntent, so every later stop naming a generation that
                        // has already been removed succeeds again. Reporting those claims a
                        // teardown that did not happen: the 2026-09-18 capture carried 14 of them
                        // against two real sessions, two per sync pass. Arming clears this memo, so
                        // a genuine teardown following a re-arm is still reported.
                        removedALiveRequest = lastRemovedPendingIntent != pendingIntent
                        lastRemovedPendingIntent = pendingIntent
                        userStateGeneration?.takeIf {
                            desired && activePendingIntent == pendingIntent
                        }
                    }
                    // Absent rather than zero whenever the count cannot be attributed. A session
                    // that armed in this process always has an entry, seeded at zero, so n=0 is
                    // observed evidence that nothing was delivered. Reaching for a default here
                    // would manufacture that finding from a teardown we simply could not name.
                    if (removedALiveRequest) {
                        logger.logPolygonApproachMonitoringStopped(
                            samplesReceived = sessionDeadlineElapsedRealtimeMs
                                ?.let { samplesByDeadline.remove(it) }
                                ?.get()
                        )
                    }
                    if (restartGeneration != null) {
                        requestUpdates(
                            pendingIntent,
                            restartGeneration,
                            synchronized(lock) { this.sessionDeadlineElapsedRealtimeMs }
                        )
                    }
                }
                .addOnFailureListener { cause ->
                    retryRemoval(pendingIntent, cause, sessionDeadlineElapsedRealtimeMs)
                }
        } catch (e: RuntimeException) {
            retryRemoval(pendingIntent, e, sessionDeadlineElapsedRealtimeMs)
        }
    }

    private fun retryRemoval(
        pendingIntent: PendingIntent,
        cause: Throwable,
        sessionDeadlineElapsedRealtimeMs: Long?
    ) {
        logger.logPolygonApproachRequestFailed(cause.message, operation = "remove_updates")
        synchronized(lock) {
            if (desired && activePendingIntent == pendingIntent) {
                // This request is wanted after all, so nothing is owed for it. These maps are keyed
                // by a per-generation PendingIntent and are the only unbounded state in this class.
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
                if (stillStale) removeUpdates(pendingIntent, sessionDeadlineElapsedRealtimeMs)
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
         * Identity is the generation in the data URI, and nothing else: `PendingIntent` equality
         * ignores extras, so the same generation with a different deadline is the SAME intent and
         * `FLAG_UPDATE_CURRENT` rewrites the live request's extras in place.
         *
         * Three things depend on that and would break silently if the deadline moved into the URI.
         * A cold process can stop a session it never started, by rebuilding the intent for that
         * generation with any deadline. The maps keyed by `PendingIntent` stay correct across a
         * deadline change. And a removal that succeeds late re-requests the right session.
         */
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

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
     * Generations whose registration has already had an ending reported, so a later removal of the
     * same registration is not reported as a second one.
     *
     * Keyed by generation because that is what identifies a *registration*: `setData` carries the
     * generation but the deadline is an extra, and `Intent.filterEquals` ignores extras, so every
     * session in a generation shares one GMS registration. A single slot cannot do this job — with
     * two generations removed in one process, a later stale removal of the first reads the slot
     * holding the second and calls itself a first ending.
     *
     * Never evicted, and that is deliberate. Nothing cancels the PendingIntent, so a stop naming an
     * old generation can succeed at any later time and there is no age after which a repeat becomes
     * impossible. Any eviction policy re-opens the phantom teardown this exists to suppress. It is
     * bounded by identify and sign-out events rather than by callbacks, which is the slowest clock
     * in this class.
     *
     * The real bound on the suppression is process lifetime, not this set: a new process starts
     * empty, so the first stale removal of a generation it inherited reports once more. A capture
     * spanning restarts will still show one such record per restart, and that is the floor of this
     * approach rather than a sign it is not working.
     */
    private val reportedEndingGenerations = mutableSetOf<Long>()

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
            // The generation of the request being REPLACED, which is not the one arming. Passing
            // the new generation here recorded the wrong registration as reported and left the old
            // one able to report a second, uncountable ending on the next stale removal.
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
            // Zero, not absent: a session that armed has observed evidence of nothing delivered,
            // and that is the finding. Absent means the count could not be attributed at all.
            // Three cases, and the middle one is why this is not a plain putIfAbsent. No entry:
            // seed one. An entry whose ending is already reported: a new session is arming on a
            // deadline a previous one used, so open a fresh account rather than counting into a
            // closed one. An entry not yet reported: keep it, because a batch that cold-started
            // this process is already counted there and this call is the session adopting it.
            // An entry may already exist because a batch cold-started this process and was
            // recorded before the session was adopted; keep its count and claim it, so `armedHere`
            // means what its name says rather than "no arm seen yet".
            val existingCount = samplesByDeadline[sessionDeadlineElapsedRealtimeMs]
            if (existingCount == null) {
                samplesByDeadline[sessionDeadlineElapsedRealtimeMs] = SessionSamples(armedHere = true)
            } else {
                existingCount.armedHere = true
            }
            // The equal-PendingIntent case ends the outgoing session without removing anything,
            // so this call reports that ending itself. Claiming the memo here is what stops it
            // being reported a second time: a later stop naming the outgoing deadline still finds
            // the registration and still succeeds, and would otherwise see no accounting entry and
            // read itself as a first ending. Reachable through a SecurityException on the request
            // below, which clears the session without removing anything.
            val endingToReport = outgoingDeadline
                ?.takeIf { previous != null && previous == current && it != sessionDeadlineElapsedRealtimeMs }
                ?.also { reportedEndingGenerations += expectedUserStateGeneration }
            Rearm(previous, current, outgoingDeadline, outgoingGeneration, endingToReport)
        }
        // Only when it is a DIFFERENT request. A re-arm for the same generation builds an equal
        // PendingIntent, so removing "the previous one" would cancel the request made just below,
        // and the removal's success listener would re-request it and log a teardown that never
        // happened.
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
        java.util.concurrent.ConcurrentHashMap<Long, SessionSamples>()

    /**
     * One session's batch count, held only until its ending is reported.
     *
     * [armedHere] separates a count worth reporting from one it cannot attribute: n=0 is evidence
     * that a session this process armed received nothing, and a guess for one it inherited. It is
     * settable because a batch can create the entry before the session is adopted, and the arm then
     * claims it.
     */
    private class SessionSamples(var armedHere: Boolean) {
        val samples = java.util.concurrent.atomic.AtomicInteger(0)
    }

    /**
     * Takes the count for the session named by [deadline], if this process has one to give.
     *
     * Removing it is what keeps this map bounded by *unreported* sessions rather than by every
     * session the process has ever run, which is what makes the age-based prune safe.
     */
    private fun consumeSessionCount(deadline: Long): ClaimedCount? {
        val entry = samplesByDeadline.remove(deadline) ?: return null
        val samples = entry.samples.get()
        return ClaimedCount(
            // Zero is a finding for a session that armed here and a guess for one inherited, where
            // the count is reported only if something was actually seen. Belt and braces today:
            // the only creator of an unarmed entry increments it in the same call, so an unarmed
            // zero is unreachable. Kept because it is the invariant the record depends on, not an
            // optimisation, and a future creator that does not increment would otherwise publish
            // n=0 for a session nothing is known about.
            samplesReceived = samples.takeIf { entry.armedHere || it > 0 },
            armedHere = entry.armedHere
        )
    }

    /**
     * A count taken from an entry, with whether this process armed the session it belongs to.
     *
     * [armedHere] is load-bearing at the point of reporting, not just in the count: an entry for a
     * session this process never armed can have been created by a batch arriving after that
     * session's ending was already reported, and reporting it would be the second record for one
     * ending. An armed entry can never be that, because arming is what opens the account.
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
     * An entry spans the earlier of arm or first batch through to the moment its ending is
     * reported, and creating one here is required rather than optional: a PendingIntent can
     * cold-start the process, and the receiver records the delivering batch before it adopts the
     * session, so counting only pre-existing entries lost that batch and reported n=0 for a session
     * that had delivered.
     *
     * Resurrection is prevented by the entry being removed when the ending is reported, not by
     * refusing to create one. A batch for a session already reported therefore finds nothing and
     * counts nowhere, which is why both readings of an absent entry stay distinguishable.
     */
    fun recordSampleDelivered(sessionDeadlineElapsedRealtimeMs: Long) {
        // Creates an entry when there is none, because a PendingIntent can cold-start the process
        // and the receiver records the delivering batch before it adopts the session. Counting only
        // pre-existing entries lost that first batch and reported n=0 for a session that had in
        // fact delivered, which is the one reading this field exists to make.
        //
        // putIfAbsent rather than merge or compute: those are API 24 and minSdk here is 21.
        val entry = samplesByDeadline.putIfAbsent(
            sessionDeadlineElapsedRealtimeMs,
            SessionSamples(armedHere = false)
        ) ?: samplesByDeadline[sessionDeadlineElapsedRealtimeMs]
        // An entry exists only until its ending is reported, so a batch for a session already
        // reported finds nothing here and cannot revive it. That is what the removal on report
        // buys, beyond keeping this map bounded by unreported sessions.
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
                        // Armed, then discarded the request we had just made. This is the shape
                        // that produces a started session which never delivers a sample. The
                        // removal below emits the ending; this only records why it was triggered.
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
            // Clearing this monitor's state is not enough on its own: the PendingIntent was minted
            // before the request failed, so `existingPendingIntentOrNull` still finds it and a
            // later stop removes it, GMS answers success for a request that was never attached, and
            // an ending gets reported for a session that never registered. Recording the ending as
            // accounted for says what is true, that this registration owes none, and drops a count
            // nothing would ever report.
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
     * [namedDeadline] is the session this removal is issued for, which is not always the live one:
     * equal intents share a registration, so a removal can name an older session while a newer one
     * is running. Deliberately not called `sessionDeadlineElapsedRealtimeMs`, which would shadow
     * the field of that name and make every comparison against the live session a comparison with
     * itself.
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
                    // Two questions, asked separately: did a teardown happen, and can it be
                    // counted.
                    //
                    // GMS reports success whether or not a request is attached, and nothing cancels
                    // the PendingIntent, so a stop naming an already-removed generation succeeds
                    // again. Reporting those claims a teardown that did not happen: the 2026-09-18
                    // capture carried 14 against two real sessions. What separates them is whether
                    // this process has torn this registration down before, and since equal intents
                    // are one registration (see [lastRemoval]) that is what the memo answers.
                    //
                    // The count comes from the accounting entry, which exists from the moment a
                    // session arms until its teardown is reported, so consuming it is atomic and
                    // belongs to that session alone. A first removal with no entry is a real
                    // ending this process cannot count: a request that outlived the process which
                    // armed it, reached either by generation alone or by a deadline this process
                    // never seeded. It is reported with no count rather than dropped.
                    //
                    // n=0 is therefore observed evidence that nothing was delivered, and absent
                    // means the ending could not be attributed. Neither is a default.
                    //
                    // The ending belongs to the session this removal names, not to the
                    // registration, and the two come apart because equal intents share one
                    // registration. A removal issued for the deadline that is still live ended
                    // nothing: this success re-issues the request below, and reporting there would
                    // claim a stop that did not happen and consume the live session's entry,
                    // leaving its real ending to report a partial count. A removal issued for an
                    // older deadline ended that session, and says so even though the registration
                    // continues.
                    val endedSession = when {
                        namedDeadline != null ->
                            namedDeadline != liveDeadline
                        else -> restartGeneration == null
                    }
                    if (endedSession) {
                        val claimed = namedDeadline?.let(::consumeSessionCount)
                        val reportedAnEnding = when {
                            // A session this process armed: its own count settles it, whatever has
                            // been reported for the registration before, which is what lets two
                            // sessions sharing one registration each report their own ending.
                            claimed != null && claimed.armedHere -> {
                                logger.logPolygonApproachMonitoringStopped(claimed.samplesReceived)
                                true
                            }
                            // An unarmed entry or none at all: either way this ending cannot be
                            // attributed to a session this process opened, so the only question is
                            // whether the registration's ending is already on the record. Without
                            // this, a batch arriving after an ending was reported created an entry
                            // and bought that ending a second record.
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
     * Carries both keys the retried removal will need. Dropping either makes the retry's success
     * path unable to tell a repeat teardown from a first one, so a removal that fails once and
     * succeeds on the retry reports the ending the memo exists to suppress.
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

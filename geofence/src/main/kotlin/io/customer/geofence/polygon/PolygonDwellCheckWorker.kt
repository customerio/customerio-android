package io.customer.geofence.polygon

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import io.customer.geofence.GeofenceConstants
import io.customer.geofence.GeofencePermissionChecker
import io.customer.geofence.di.geofenceRegionStore
import io.customer.geofence.di.polygonDwellCheckScheduler
import io.customer.geofence.di.polygonGeofenceServiceController
import io.customer.geofence.store.GeofenceDwellVisit
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.transitionRevision
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import io.customer.sdk.data.store.SecureUserStore
import java.util.concurrent.TimeUnit

/** A pending polygon visit's next background look, on [PolygonBootSessionProvider]'s boot clock. */
internal data class PolygonDwellCheck(
    val polygonId: String,
    val visitId: String,
    val atElapsedMs: Long
)

/**
 * Which polygon visits a background check could still prove, and when each may next look: at its
 * threshold, and no sooner than the dwell-proof spacing after its last request. A visit that spent
 * its budget, belongs to another boot, user or revision, or lost its registration needs none, so
 * nothing is scheduled for it. Reads only.
 */
internal class PolygonDwellCheckPlanner(
    private val store: GeofenceRegionStore,
    private val secureUserStore: SecureUserStore,
    private val bootSessionProvider: PolygonBootSessionProvider,
    private val permissionChecker: GeofencePermissionChecker
) {
    fun pendingChecks(): List<PolygonDwellCheck> {
        // Without fine location no fix could prove a stay, and GMS reports no edge to end the visit,
        // so scheduling would only wake the app until the visit ends. The next reconcile picks it up.
        if (!permissionChecker.hasFineLocationPermission()) return emptyList()
        val userId = secureUserStore.getUserId()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        if (store.activeUserSessionId() != userId) return emptyList()
        val routableIds = store.getRegisteredIds() intersect store.getRoutableRegisteredIds()
        val enteredIds = store.getEnteredIds()
        val generation = store.userStateGeneration()
        val bootSessionId = bootSessionProvider.currentSessionId()
        return store.getDwellVisits().mapNotNull { visit ->
            val polygonId = visit.geofenceId
            if (polygonId !in routableIds || polygonId !in enteredIds) return@mapNotNull null
            val region = store.getCachedRegion(polygonId)?.takeIf {
                it.polygonGeometryOrNull() != null &&
                    it.dwellThresholdSeconds in 1..GeofenceConstants.MAX_DWELL_THRESHOLD_SECONDS
            } ?: return@mapNotNull null
            if (!awaitsCheck(visit, region.transitionRevision(), generation, bootSessionId)) return@mapNotNull null
            val enteredAt = visit.enteredAtElapsedMs ?: return@mapNotNull null
            val dueAt = enteredAt + region.dwellThresholdSeconds * MILLIS_PER_SECOND
            val spacedAt = visit.lastDwellProofRequestElapsedMs
                ?.plus(GeofenceConstants.DWELL_PROOF_REQUEST_SPACING_MS) ?: dueAt
            PolygonDwellCheck(polygonId, visit.visitId, maxOf(dueAt, spacedAt))
        }
    }

    private fun awaitsCheck(
        visit: GeofenceDwellVisit,
        revision: Int,
        generation: Long,
        bootSessionId: String
    ): Boolean = !visit.emitted &&
        visit.dwellReservation == null &&
        visit.regionRevision == revision &&
        visit.userStateGeneration == generation &&
        visit.bootSessionId == bootSessionId &&
        visit.dwellProofRequests < GeofenceConstants.MAX_DWELL_PROOF_REQUESTS_PER_VISIT

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * Keeps one WorkManager job due at the earliest pending polygon visit's next check, so a stay the
 * OS reports no fix for still gets looked at once its threshold passes. GMS only monitors circles, so
 * nothing else wakes the app at a polygon's threshold. Each check spends one of the visit's
 * dwell-proof requests, so a visit costs at most [GeofenceConstants.MAX_DWELL_PROOF_REQUESTS_PER_VISIT]
 * precise fixes however often it is checked. WorkManager may run a job late in Doze; a late check
 * still needs the same strict proof, so lateness costs only timeliness.
 */
internal interface PolygonDwellCheckScheduler {
    /**
     * Schedules the next check, or cancels it when no visit awaits one. Call after anything that may
     * start, end or settle a polygon visit. Never awaits; a lost enqueue is redone by the next call.
     */
    fun reconcile()

    /** The checks due now. Each is counted as attempted, so a check that asks nothing is not repeated at once. */
    fun takeDueChecks(): List<PolygonDwellCheck>

    /**
     * [check] was refused by another request's cooldown: it runs again after [delayMs], uncounted for
     * the first [WorkManagerPolygonDwellCheckScheduler.MAX_UNCOUNTED_RETRIES_PER_VISIT] such refusals of a visit, since edge requests,
     * which no budget bounds, could otherwise renew the cooldown before every retry.
     */
    fun retryUncounted(check: PolygonDwellCheck, delayMs: Long)

    /** Brackets a job's run: calls meanwhile wait for its end, which reconciles once. */
    fun beginRun()

    fun endRun()
}

internal class WorkManagerPolygonDwellCheckScheduler(
    private val workManagerProvider: CustomerIOWorkManagerProvider,
    private val planner: PolygonDwellCheckPlanner,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime
) : PolygonDwellCheckScheduler {
    private data class Attempts(val count: Int, val notBeforeElapsedMs: Long, val uncounted: Int = 0)

    /**
     * Held across planning and the WorkManager call, so a reconcile working from an older plan can
     * never cancel or replace the job a newer one just enqueued. Neither call blocks for long.
     */
    private val lock = Any()
    private var runs = 0

    /** What this process last asked WorkManager for; unknown until the first reconcile. */
    private var scheduled: Long? = null
    private var scheduledKnown = false

    /**
     * Checks a job made, by visit, whether or not their request was charged: one that asked nothing,
     * say because a fix had just been requested, must still wait the spacing. In memory: a new process
     * starts without them, but then nothing in it can have refused a request.
     */
    private val attempts = mutableMapOf<String, Attempts>()

    override fun reconcile() = synchronized(lock) {
        if (runs == 0) scheduleLocked()
    }

    override fun takeDueChecks(): List<PolygonDwellCheck> = synchronized(lock) {
        val now = elapsedRealtimeMs()
        nextChecksLocked().filter { it.atElapsedMs <= now }.onEach { check ->
            val tried = attempts[check.visitId]
            attempts[check.visitId] = Attempts(
                count = (tried?.count ?: 0) + 1,
                notBeforeElapsedMs = now + GeofenceConstants.DWELL_PROOF_REQUEST_SPACING_MS,
                uncounted = tried?.uncounted ?: 0
            )
        }
    }

    override fun retryUncounted(check: PolygonDwellCheck, delayMs: Long) = synchronized(lock) {
        val tried = attempts[check.visitId] ?: return@synchronized
        val refund = tried.uncounted < MAX_UNCOUNTED_RETRIES_PER_VISIT
        attempts[check.visitId] = Attempts(
            count = if (refund) tried.count - 1 else tried.count,
            notBeforeElapsedMs = elapsedRealtimeMs() + delayMs,
            uncounted = if (refund) tried.uncounted + 1 else tried.uncounted
        )
    }

    override fun beginRun() = synchronized(lock) {
        runs += 1
        // This job was the schedule: its end must enqueue again even for an unchanged time.
        scheduledKnown = false
    }

    override fun endRun() = synchronized(lock) {
        runs -= 1
        if (runs == 0) scheduleLocked()
    }

    private fun nextChecksLocked(): List<PolygonDwellCheck> {
        val planned = planner.pendingChecks()
        attempts.keys.retainAll(planned.mapTo(mutableSetOf(), PolygonDwellCheck::visitId))
        return planned.mapNotNull { check ->
            val tried = attempts[check.visitId] ?: return@mapNotNull check
            if (tried.count >= GeofenceConstants.MAX_DWELL_PROOF_REQUESTS_PER_VISIT) return@mapNotNull null
            check.copy(atElapsedMs = maxOf(check.atElapsedMs, tried.notBeforeElapsedMs))
        }
    }

    private fun scheduleLocked() {
        val next = nextChecksLocked().minOfOrNull(PolygonDwellCheck::atElapsedMs)
        if (scheduledKnown && scheduled == next) return
        val workManager = workManagerProvider.getWorkManager() ?: return
        scheduled = next
        scheduledKnown = true
        val enqueued = runCatching {
            if (next == null) {
                workManager.cancelUniqueWork(POLYGON_DWELL_CHECK_WORK)
            } else {
                val request = OneTimeWorkRequestBuilder<PolygonDwellCheckWorker>()
                    .setInitialDelay((next - elapsedRealtimeMs()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                    .addTag(WORK_MANAGER_TAG_CIO)
                    .addTag(WORK_MANAGER_TAG_POLYGON_DWELL_CHECK)
                    .build()
                // REPLACE re-anchors to the recomputed time. Not issued while a job runs: its own end
                // reconciles, after its last suspension.
                workManager.enqueueUniqueWork(POLYGON_DWELL_CHECK_WORK, ExistingWorkPolicy.REPLACE, request)
            }
        }.isSuccess
        if (!enqueued) scheduledKnown = false
    }

    internal companion object {
        const val POLYGON_DWELL_CHECK_WORK = "cio-polygon-dwell-check"
        const val WORK_MANAGER_TAG_CIO = "cio-requests"
        const val WORK_MANAGER_TAG_POLYGON_DWELL_CHECK = "cio-polygon-dwell-check"

        /** Per visit and process; past it a cooldown refusal counts as an attempt. */
        const val MAX_UNCOUNTED_RETRIES_PER_VISIT = 8
    }
}

/**
 * Looks at each polygon visit whose check is due, through
 * [PolygonGeofenceServiceController.onDwellCheck]: one precise fix, charged to the visit's budget,
 * judged by the same rules as any other fix. Then schedules the next check, if any.
 */
internal class PolygonDwellCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        SDKComponent.setupAndroidComponent(context = applicationContext)
        val android = SDKComponent.android()
        val scheduler = android.polygonDwellCheckScheduler
        scheduler.beginRun()
        try {
            val controller = android.polygonGeofenceServiceController
            // Snapshot before any await, so a teardown or identify meanwhile retires this run.
            val expectedUserStateGeneration = android.geofenceRegionStore.userStateGeneration()
            val expectedTeardownGeneration = controller.teardownGeneration()
            // Cancellable: a stop mid-check can waste at most that check's charged request, which the
            // visit's budget already bounds, while the evaluation after it may wait on the outbox.
            scheduler.takeDueChecks().forEach { check ->
                controller.onDwellCheck(
                    polygonId = check.polygonId,
                    visitId = check.visitId,
                    expectedUserStateGeneration = expectedUserStateGeneration,
                    expectedTeardownGeneration = expectedTeardownGeneration
                )?.let { delayMs -> scheduler.retryUncounted(check, delayMs) }
            }
        } finally {
            scheduler.endRun()
        }
        return Result.success()
    }
}

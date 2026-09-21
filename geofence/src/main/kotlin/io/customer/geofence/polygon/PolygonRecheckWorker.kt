package io.customer.geofence.polygon

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import io.customer.geofence.PolygonRecheckSkip
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.geofenceRegionStore
import io.customer.geofence.di.polygonFreshFixSource
import io.customer.geofence.di.polygonGeofenceServiceController
import io.customer.geofence.di.polygonRecheckScheduler
import io.customer.geofence.distanceTo
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import java.util.concurrent.TimeUnit

/**
 * Schedules the periodic polygon re-check.
 *
 * The gap this closes is a callback that never arrives at all. A polygon is woken by its wake
 * circle, and GMS is documented to re-report ENTER for a fence added around a device already
 * inside one — but `emitInitialEnters` exists because it *unreliably drops* that for a stationary
 * device, and that backstop skips polygons (`if (region.isPolygon) continue`), because the sync fix
 * carries no accuracy evidence to judge a ring with. So a device sitting inside a wake circle when
 * that circle is registered can have no callback and no backstop, and nothing re-derives a polygon
 * arrival. Until the user leaves the circle and comes back, the visit is unreachable.
 *
 * Scheduled while any polygon is registered and cancelled when none is. [ExistingPeriodicWorkPolicy.KEEP]
 * is deliberate: every sync calls the scheduler, and REPLACE would re-anchor the period on each
 * one, so a frequently-syncing app would push the next run out indefinitely and never re-check.
 */
internal interface PolygonRecheckScheduler {
    /** False when WorkManager is unavailable, which leaves the callback path as the only wake. */
    fun schedule(): Boolean

    fun cancel(): Boolean
}

internal class WorkManagerPolygonRecheckScheduler(
    private val workManagerProvider: CustomerIOWorkManagerProvider
) : PolygonRecheckScheduler {
    override fun schedule(): Boolean {
        val workManager = workManagerProvider.getWorkManager() ?: return false
        val request = PeriodicWorkRequestBuilder<PolygonRecheckWorker>(
            RECHECK_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
            .addTag(WORK_MANAGER_TAG_CIO)
            .addTag(WORK_MANAGER_TAG_POLYGON_RECHECK)
            .build()
        // Not awaited. Unlike the approach queue, which is enqueued from a broadcast that must not
        // finish() before WorkManager commits, this is called from ordinary code with no deadline
        // behind it, and a lost enqueue is recovered by the next sync calling this again.
        return runCatching {
            workManager.enqueueUniquePeriodicWork(
                POLYGON_RECHECK_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }.isSuccess
    }

    override fun cancel(): Boolean {
        val workManager = workManagerProvider.getWorkManager() ?: return false
        return runCatching { workManager.cancelUniqueWork(POLYGON_RECHECK_WORK) }.isSuccess
    }

    internal companion object {
        const val POLYGON_RECHECK_WORK = "cio-polygon-recheck"
        const val WORK_MANAGER_TAG_CIO = "cio-requests"
        const val WORK_MANAGER_TAG_POLYGON_RECHECK = "cio-polygon-recheck"

        /**
         * WorkManager's own floor. Measured on an API 37 emulator it actually fires at 18-20 min,
         * so treat this as a lower bound on the latency, not the latency. A stop shorter than that
         * in a dead zone is still missed, which is accepted: see the wake architecture record.
         */
        const val RECHECK_INTERVAL_MINUTES = 15L
        // KEEP has a consequence worth knowing: a device already holding this work keeps the
        // interval and constraints it was enqueued with, so changing either here does not reach it
        // until something cancels the work. The worker's own retirement when nothing is registered
        // is what eventually re-anchors it.
    }
}

/**
 * Asks where the device is and feeds the answer through the ordinary coarse-ENTER path.
 *
 * It deliberately adds no evaluation of its own. Each admitted polygon goes through the same
 * [PolygonGeofenceServiceController.activate] a GMS callback uses, so dedupe, the accuracy
 * decision, the on-demand precise fix, the corroboration hold and emission are all the reviewed
 * paths rather than a parallel set.
 */
internal class PolygonRecheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        SDKComponent.setupAndroidComponent(context = applicationContext)
        val logger = SDKComponent.geofenceLogger
        val store = SDKComponent.android().geofenceRegionStore
        // The ownership snapshot is taken before any of the work it describes, not after.
        //
        // Both of these say which session this run belongs to, and both are checked when activate
        // takes the controller lock. Read after the catalog and permission work, a teardown or an
        // identify landing during that work would be captured as though it had always been the
        // current session: the run would hand over the post-teardown token, match it, and re-arm
        // the polygon it had just removed. Raised by Shahroz on #896 with a reproduction.
        //
        // Cancellation cannot enforce this boundary either, here or after the wait: the coroutine
        // may already be past the suspension point when the teardown lands.
        val expectedUserStateGeneration = store.userStateGeneration()
        val expectedTeardownGeneration =
            SDKComponent.android().polygonGeofenceServiceController.teardownGeneration()
        val routableIds = store.getRoutableRegisteredIds()
        val polygons = store.getCachedRegions().filter { it.id in routableIds && it.isPolygon }
        if (polygons.isEmpty()) {
            // Self-healing rather than relying on every cancel site being wired: if the set is
            // empty this work has nothing to do again, so it retires itself. A missed cancel
            // therefore costs one wake, not a permanent periodic job.
            logger.logPolygonRecheckSkipped(PolygonRecheckSkip.NOTHING_REGISTERED)
            SDKComponent.android().polygonRecheckScheduler.cancel()
            return Result.success()
        }
        if (!hasLocationPermission()) {
            // Told apart from NO_FIX deliberately. awaitFreshFix reports a revoked permission and a
            // silent GPS as the same null, and a capture that cannot separate them reads a
            // permission loss as poor reception for as long as the user leaves it revoked.
            logger.logPolygonRecheckSkipped(PolygonRecheckSkip.NO_PERMISSION)
            return Result.success()
        }
        // Balanced, not high accuracy. This fix answers one question — is the device inside a wake
        // circle, which is hundreds of metres across — and a GPS-grade answer to it is waste. The
        // registered polygons are only the nearest set, so a user kilometres from all of them would
        // otherwise pay a 20 s GPS wake every interval forever. Precision is bought downstream, by
        // the undecided path in activate, and only once a cheap fix has shown it could matter.
        val fix = SDKComponent.android().polygonFreshFixSource.awaitFreshFix(
            timeoutMs = RECHECK_FIX_TIMEOUT_MS,
            priority = PolygonFixPriority.BALANCED
        )
        if (fix == null) {
            logger.logPolygonRecheckSkipped(PolygonRecheckSkip.NO_FIX)
            return Result.success()
        }
        // The wake circle, with no accuracy margin, matching the approach admission gate. A
        // polygon whose circle does not contain this fix has nothing to re-check.
        val admitted = polygons.filter { it.distanceTo(fix.latitude, fix.longitude) <= it.radius }
        // The departure half of the same fix, and the reason this path is safe to activate from at
        // all. activate() records the polygon coarse-inside, and the only ordinary route that
        // clears it is a GMS coarse EXIT. When this worker recovers an ENTER that GMS never
        // issued, GMS holds no inside state for that fence, so it may issue no EXIT and the
        // polygon stays in the active set for the life of the install, re-evaluated by every fix
        // that reaches the engine.
        //
        // The fix's accuracy is subtracted rather than gated on MAX_DECISIVE_FIX_ACCURACY_METERS.
        // That ceiling is for ring-level decisions, where the margin is tens of metres; here the
        // margin is the whole accuracy value plus the circle's slack over the ring, so the
        // predicate is self-limiting and a coarse fix simply fails it. Requiring the ceiling too
        // would make this unreachable from the balanced fix asked for above, leaving dead code.
        val activeIds = store.getActivePolygonIds()
        val departed = polygons.filter { region ->
            region.id in activeIds &&
                // Location.accuracy reports 0 when unset, which would remove the margin and clear
                // on a fix whose error is unknown. GMS fixes carry it; this keeps "decisively" a
                // property of the predicate rather than of the provider.
                fix.hasAccuracy() &&
                region.distanceTo(fix.latitude, fix.longitude) - fix.accuracy > region.radius
        }
        logger.logPolygonRecheckRan(
            location = fix,
            candidateCount = polygons.size,
            admittedIds = admitted.map { it.id }.sorted(),
            clearedIds = departed.map { it.id }.sorted()
        )
        val controller = SDKComponent.android().polygonGeofenceServiceController
        // onCoarseExit, not the store flag and not a direct deactivate. It is the reviewed order:
        // record outside, drop a duplicate delivery, evaluate the fix, keep the polygon active if
        // the arrival was already committed, and otherwise deactivate and report the holds it
        // discarded. Clearing the flag alone leaves an already-emitted business EXIT with nothing
        // to re-run, so the polygon would stay active regardless.
        departed.forEach { region ->
            controller.onCoarseExit(
                polygonId = region.id,
                triggeringLocation = fix,
                expectedUserStateGeneration = expectedUserStateGeneration,
                expectedTeardownGeneration = expectedTeardownGeneration
            )
        }
        admitted.forEach { region ->
            controller.activate(
                polygonId = region.id,
                triggeringLocation = fix,
                expectedUserStateGeneration = expectedUserStateGeneration,
                expectedTeardownGeneration = expectedTeardownGeneration
            )
        }
        return Result.success()
    }

    /**
     * Checked before asking rather than relying on the null. The worker runs while the app is
     * backgrounded, which is exactly when a user is most likely to have revoked location, and the
     * record has to say which happened.
     */
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            applicationContext,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        /**
         * Longer than the callback path's budget, because this runs in a job with minutes of
         * headroom rather than inside a broadcast. It is a coarse fix, so the wait is for one to
         * arrive at all rather than for it to sharpen.
         */
        const val RECHECK_FIX_TIMEOUT_MS = 20_000L
    }
}

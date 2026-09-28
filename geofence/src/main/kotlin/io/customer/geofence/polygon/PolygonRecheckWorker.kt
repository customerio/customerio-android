package io.customer.geofence.polygon

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.PolygonRecheckSkip
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.geofenceRegionStore
import io.customer.geofence.di.polygonFreshFixSource
import io.customer.geofence.di.polygonGeofenceServiceController
import io.customer.geofence.di.polygonRecheckScheduler
import io.customer.geofence.distanceTo
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import java.util.concurrent.TimeUnit

/**
 * Schedules the periodic polygon re-check while any polygon is registered. It covers a device
 * already inside a wake circle when it is registered: GMS can drop that initial ENTER for a
 * stationary device, and `emitInitialEnters` skips polygons, so otherwise the visit is unreachable
 * until the user leaves and returns.
 *
 * [ExistingPeriodicWorkPolicy.KEEP] because every sync calls this, and REPLACE would re-anchor the
 * period each time, so a frequently syncing app would never re-check. KEEP also means a device
 * already holding the work keeps its old interval and constraints until the work is cancelled.
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
        // Not awaited: unlike the approach queue this is not called from a broadcast, and a lost
        // enqueue is recovered by the next sync calling this again.
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

        /** WorkManager's minimum periodic interval; runs can fire later, so this is a lower bound. */
        const val RECHECK_INTERVAL_MINUTES = 15L
    }
}

/**
 * Asks where the device is and feeds the answer through the same
 * [PolygonGeofenceServiceController.activate] a GMS callback uses, adding no evaluation of its own.
 */
internal class PolygonRecheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        SDKComponent.setupAndroidComponent(context = applicationContext)
        val logger = SDKComponent.geofenceLogger
        val store = SDKComponent.android().geofenceRegionStore
        // Session snapshot taken before any work. Read later, a teardown or identify landing
        // during that work would be captured as current, and the run would re-arm the polygon just
        // removed. Cancellation cannot enforce this: the coroutine may be past its suspension point
        // when the teardown lands.
        val expectedUserStateGeneration = store.userStateGeneration()
        val expectedTeardownGeneration =
            SDKComponent.android().polygonGeofenceServiceController.teardownGeneration()
        val polygons = registeredPolygons(store)
        if (polygons.isEmpty()) {
            // Retires itself, so a missed cancel costs one wake, not a permanent periodic job.
            logger.logPolygonRecheckSkipped(PolygonRecheckSkip.NOTHING_REGISTERED)
            // Cancel, then re-read, then re-schedule if the catalog filled meanwhile, so a sync that
            // scheduled after the read above is not cancelled away. Syncs write routable ids before
            // scheduling, so either the re-read sees the write or the sync's schedule() lands after
            // this cancel and survives it.
            //
            // Relies on WorkManager 2.10.5 internals, not its public contract: cancel and enqueue
            // from one process run in call order; cancelling a RUNNING row marks it CANCELLED at
            // once; KEEP inserts over a CANCELLED row. Neither call suspends, so this worker's own
            // cancellation cannot interleave between them.
            val scheduler = SDKComponent.android().polygonRecheckScheduler
            scheduler.cancel()
            // KEEP, so re-asserting over a schedule the sync already made leaves that one alone.
            if (registeredPolygons(store).isNotEmpty()) scheduler.schedule()
            return Result.success()
        }
        if (!hasLocationPermission()) {
            // Distinct from NO_FIX: awaitFreshFix returns null for both, and a capture must not read
            // a permission loss as poor reception.
            logger.logPolygonRecheckSkipped(PolygonRecheckSkip.NO_PERMISSION)
            return Result.success()
        }
        // Balanced, not high accuracy: this only asks whether the device is inside a wake circle,
        // and a user kilometres from every polygon would otherwise pay for GPS every interval.
        // Precision is bought downstream by activate's undecided path when it could matter.
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
        // The departure half, which makes activating from here safe: activate() records the polygon
        // coarse-inside and only a coarse EXIT clears it, and GMS may never issue one for an ENTER
        // it never reported. Accuracy is subtracted rather than gated on the arrival ceiling, which
        // the balanced fix above would rarely meet.
        val activeIds = store.getActivePolygonIds()
        val departed = polygons.filter { region ->
            region.id in activeIds &&
                // Location.accuracy is 0 when unset, which would remove the margin.
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
        // onCoarseExit, not the store flag or a direct deactivate: it drops a duplicate delivery,
        // evaluates the fix, keeps a committed arrival active, and otherwise deactivates and
        // reports the holds it discarded.
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

    /** Read the same way both times the run needs them, so the retirement re-check cannot drift. */
    private fun registeredPolygons(store: GeofenceRegionStore): List<GeofenceRegion> {
        val routableIds = store.getRoutableRegisteredIds()
        return store.getCachedRegions().filter { it.id in routableIds && it.isPolygon }
    }

    /** Checked up front because a null fix cannot say whether permission was revoked. */
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
        /** Longer than the callback path's budget: this runs in a job, not inside a broadcast. */
        const val RECHECK_FIX_TIMEOUT_MS = 20_000L
    }
}

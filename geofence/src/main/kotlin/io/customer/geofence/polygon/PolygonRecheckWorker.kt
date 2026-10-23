package io.customer.geofence.polygon

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import io.customer.geofence.DwellDiagnosticEvent
import io.customer.geofence.DwellDiagnosticRecord
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.PolygonRecheckSkip
import io.customer.geofence.di.dwellDiagnosticRecorder
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.geofencePackageInfo
import io.customer.geofence.di.geofenceRegionStore
import io.customer.geofence.di.polygonBootSessionProvider
import io.customer.geofence.di.polygonDwellCheckScheduler
import io.customer.geofence.di.polygonFreshFixSource
import io.customer.geofence.di.polygonGeofenceServiceController
import io.customer.geofence.di.polygonRecheckScheduler
import io.customer.geofence.distanceTo
import io.customer.geofence.restoreAfterOsWipe
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.osRegistrationsWiped
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import java.util.concurrent.TimeUnit

/**
 * Covers a device already inside a wake circle at registration, whose initial ENTER GMS can drop.
 * [ExistingPeriodicWorkPolicy.KEEP] because every sync calls this and REPLACE would re-anchor the
 * period; existing work therefore keeps its old interval and constraints until cancelled.
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
        // Not awaited: a lost enqueue is recovered by the next sync calling this again.
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

        /** WorkManager's minimum periodic interval. */
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
        // First after setup, before any read or await: one record per run, bound to the owner at dispatch.
        recordWake()
        val logger = SDKComponent.geofenceLogger
        val store = SDKComponent.android().geofenceRegionStore
        // Snapshot before any work, or a teardown or identify landing mid-run would read as current
        // and the run would re-arm the polygon just removed; cancellation cannot enforce this.
        val expectedUserStateGeneration = store.userStateGeneration()
        val expectedTeardownGeneration =
            SDKComponent.android().polygonGeofenceServiceController.teardownGeneration()
        val polygons = registeredPolygons(store)
        if (polygons.isEmpty()) {
            // Retires itself, so a missed cancel costs one wake, not a permanent periodic job.
            logger.logPolygonRecheckSkipped(PolygonRecheckSkip.NOTHING_REGISTERED)
            // Syncs write routable ids before scheduling, so either the re-read sees the write or
            // the sync's schedule() lands after this cancel. Relies on WorkManager internals,
            // checked against 2.12.0: in-order cancel/enqueue, RUNNING rows cancelled at once, KEEP
            // over CANCELLED, neither call suspends.
            val scheduler = SDKComponent.android().polygonRecheckScheduler
            scheduler.cancel()
            if (registeredPolygons(store).isNotEmpty()) scheduler.schedule()
            return Result.success()
        }
        if (!hasLocationPermission()) {
            // Checked up front: awaitFreshFix returns null for both this and NO_FIX.
            logger.logPolygonRecheckSkipped(PolygonRecheckSkip.NO_PERMISSION)
            return Result.success()
        }
        // A fix now could qualify a dwell for a visit the monitoring gap has already broken, before
        // the sync that ends such visits runs.
        fun registrationsWiped() = store.osRegistrationsWiped(
            bootSessionId = SDKComponent.android().polygonBootSessionProvider.currentSessionId(),
            nowElapsedMs = SystemClock.elapsedRealtime(),
            packageUpdateTimeMs = SDKComponent.android().geofencePackageInfo.lastUpdateTimeMs()
        )
        if (registrationsWiped()) {
            logger.logPolygonRecheckSkipped(PolygonRecheckSkip.REGISTRATIONS_WIPED)
            // A reboot or app update dropped the registrations, and no launch may come to restore them
            // for days. Restored here as a boot does: visits that cannot span the gap end, and the
            // re-added fences report ENTER for any the device is inside, so ENTER and EXIT resume.
            restoreAfterOsWipe(REASON_WIPE_RESTORE)
            return Result.success()
        }
        // A safety net for dwell checks: one whose own enqueue failed, with nothing else to re-plan it,
        // is scheduled again within this period.
        SDKComponent.android().polygonDwellCheckScheduler.reconcile()
        // Balanced: this only asks about wake circles; activate's undecided path buys precision
        // when it could matter.
        val fix = SDKComponent.android().polygonFreshFixSource.awaitFreshFix(
            timeoutMs = RECHECK_FIX_TIMEOUT_MS,
            priority = PolygonFixPriority.BALANCED
        )
        if (fix == null) {
            logger.logPolygonRecheckSkipped(PolygonRecheckSkip.NO_FIX)
            return Result.success()
        }
        // No accuracy margin, matching the approach admission gate.
        val admitted = polygons.filter { it.distanceTo(fix.latitude, fix.longitude) <= it.radius }
        // Makes activating here safe: only a coarse EXIT clears activate()'s coarse-inside. Accuracy
        // is subtracted, not gated on the arrival ceiling, which a balanced fix rarely meets.
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
        // keeps a committed arrival active, and reports the holds it discarded.
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

    /** Observational only: a diagnostics failure must not change what this run does. */
    private fun recordWake() {
        runCatching {
            val recorder = SDKComponent.android().dwellDiagnosticRecorder
            recorder.record(
                DwellDiagnosticRecord(
                    event = DwellDiagnosticEvent.WAKE_WORKER,
                    atElapsedMs = SystemClock.elapsedRealtime()
                )
            )
        }
    }

    private fun registeredPolygons(store: GeofenceRegionStore): List<GeofenceRegion> {
        val routableIds = store.getRoutableRegisteredIds()
        return store.getCachedRegions().filter { it.id in routableIds && it.isPolygon }
    }

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

        const val REASON_WIPE_RESTORE = "recheck-wipe-restore"
    }
}

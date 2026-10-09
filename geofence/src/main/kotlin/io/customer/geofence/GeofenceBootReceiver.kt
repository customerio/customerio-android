package io.customer.geofence

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.geofencePermissionChecker
import io.customer.geofence.di.geofenceRegionStore
import io.customer.geofence.di.geofenceRepository
import io.customer.geofence.di.polygonGeofenceServiceController
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Re-registers cached geofences after a reboot, which drops every OS geofence registration. */
class GeofenceBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val logger = SDKComponent.geofenceLogger
        // goAsync keeps the process alive until GMS commits the restored registration.
        val pendingResult = goAsync()
        try {
            SDKComponent.setupAndroidComponent(context = context)
            val scope = SDKComponent.scopeProvider.geofenceScope
            logger.logModuleWoke(GeofenceLaunchReason.BOOT_RESTORE)
            logger.logSyncTriggered(REASON_BOOT_RESTORE)
            scope.launch {
                try {
                    restore()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.logSyncFailed("BootReceiver restore failed: ${e.message}")
                } finally {
                    pendingResult.finish()
                    scope.cancel()
                }
            }
        } catch (e: Throwable) {
            logger.logSyncFailed("BootReceiver setup failed: ${e.message}")
            pendingResult.finish()
        }
    }

    @VisibleForTesting
    internal suspend fun restore() {
        val android = SDKComponent.android()
        // Reboot destroys the GMS monitoring session. A persisted entry can no longer prove
        // continuous presence across that gap, even when registration is restored successfully.
        android.geofenceRegionStore.clearDwellVisits()
        if (!android.geofencePermissionChecker.hasRequiredLocationPermissions()) {
            SDKComponent.geofenceLogger.logSyncSkippedNoPermission(REASON_BOOT_RESTORE)
            return
        }
        android.polygonGeofenceServiceController.beginUserSessionForCurrentUser()
        @SuppressLint("MissingPermission")
        android.geofenceRepository.restoreFromCache()
    }

    internal companion object {
        private const val REASON_BOOT_RESTORE = "boot-restore"
    }
}

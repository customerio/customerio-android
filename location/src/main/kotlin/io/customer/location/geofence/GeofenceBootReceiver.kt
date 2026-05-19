package io.customer.location.geofence

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import io.customer.location.geofence.di.geofenceLogger
import io.customer.location.geofence.di.geofencePermissionChecker
import io.customer.location.geofence.di.geofenceRepository
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Re-registers persisted geofences after device reboot — the OS drops all
 * registrations across a reboot, so we restore from our cache the first time
 * the device comes back up.
 */
class GeofenceBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val logger = SDKComponent.geofenceLogger
        // goAsync keeps the process alive until the restore call resolves;
        // without it the OS may kill us between launching the coroutine and
        // GMS committing the new registration.
        val pendingResult = goAsync()
        try {
            SDKComponent.setupAndroidComponent(context = context)
            val scope = SDKComponent.scopeProvider.geofenceScope
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
        if (!android.geofencePermissionChecker.hasRequiredLocationPermissions()) {
            SDKComponent.geofenceLogger.logSyncSkippedNoPermission(REASON_BOOT_RESTORE)
            return
        }
        @SuppressLint("MissingPermission")
        android.geofenceRepository.restoreFromCache()
    }

    internal companion object {
        private const val REASON_BOOT_RESTORE = "boot-restore"
    }
}

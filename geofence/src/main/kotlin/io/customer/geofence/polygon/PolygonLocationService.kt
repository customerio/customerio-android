package io.customer.geofence.polygon

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.customer.geofence.R
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.polygonFineLocationStream
import io.customer.geofence.di.polygonGeofenceServiceController
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent

/**
 * The location foreground service behind [io.customer.geofence.PolygonTrackingMode.CONTINUOUS].
 *
 * Promotion is attempted first and acknowledged only once
 * [startForeground][ServiceCompat.startForeground] has returned successfully. Android can refuse it
 * — a background start on 12+, a missing `FOREGROUND_SERVICE_LOCATION` grant on 14+, a blocked
 * notification channel — and a refusal must leave no impression that fine sampling is running:
 * [PolygonGeofenceServiceController.onServicePromotionFailed] releases the promotion latch, and the
 * SDK carries the session on the responsive path instead.
 */
internal class PolygonLocationService : Service() {
    private var streamRegistrationGeneration: Long? = null
    private var serviceStartGeneration: Long? = null
    private var promoted = false

    override fun onCreate() {
        super.onCreate()
        runCatching { SDKComponent.setupAndroidComponent(context = this) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val generation = intent?.getLongExtra(EXTRA_SERVICE_START_GENERATION, Long.MIN_VALUE)
            ?.takeIf { it != Long.MIN_VALUE }
        generation?.let { serviceStartGeneration = it }
        val controller = runCatching { SDKComponent.android().polygonGeofenceServiceController }
            .getOrNull()
        if (controller == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // The one check that runs before promotion: a sticky restart, or a start racing a switch
        // back to responsive mode, must not put a location notification in front of a host that has
        // not opted in. It is a lock-free read of the persisted mode — the controller lock is held
        // across catalog work by recovery and approach batches, and blocking this thread on it would
        // trade a stray notification for a missed promotion deadline, which kills the process. The
        // snapshot can therefore be one write stale; the locked recheck inside
        // startFineLocationStream below is what decides whether anything is actually sampled.
        val continuousEnabled = runCatching { controller.isContinuousTrackingModeSnapshot() }
            .getOrDefault(false)
        if (!continuousEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Otherwise promote before evaluating anything. The system holds this process to the
        // promise made by startForegroundService, and a start cancelled between the request and
        // here has to keep it too — hence promote, then stop, rather than never promoting.
        val promotionFailure = startForegroundSafely()
        if (promotionFailure != null) {
            runCatching { controller.onServicePromotionFailed(generation, promotionFailure) }
            stopSelf()
            return START_NOT_STICKY
        }
        promoted = true
        generation?.let { runCatching { controller.onServicePromoted(it) } }

        if (!hasFineLocationPermission()) {
            runCatching {
                controller.onServicePromotionFailed(generation, "ACCESS_FINE_LOCATION is not granted")
            }
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            streamRegistrationGeneration = controller.startFineLocationStream(::stopSelf)
            if (streamRegistrationGeneration == null) stopSelf()
        } catch (e: RuntimeException) {
            SDKComponent.geofenceLogger.logPolygonMonitoringFailed(e.message)
            stopSelf()
            return START_NOT_STICKY
        }
        // Sticky so a process killed while inside a trigger circle resumes sampling. A restart
        // arrives with a null intent, promotes again above, and re-validates against current state.
        return START_STICKY
    }

    override fun onDestroy() {
        streamRegistrationGeneration?.let { generation ->
            runCatching { SDKComponent.android().polygonFineLocationStream.stopIfCurrent(generation) }
        }
        runCatching {
            SDKComponent.android().polygonGeofenceServiceController.onServiceDestroyed(
                serviceStartGeneration
            )
        }
        if (promoted) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Returns `null` on success, or the reason promotion was refused. */
    private fun startForegroundSafely(): String? = try {
        createNotificationChannel()
        val appIcon = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_mylocation
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(appIcon)
            .setContentTitle(getString(R.string.cio_polygon_geofence_notification_title))
            .setContentText(getString(R.string.cio_polygon_geofence_notification_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        null
    } catch (e: RuntimeException) {
        e.message ?: e::class.java.simpleName
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.cio_polygon_geofence_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun hasFineLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    internal companion object {
        const val EXTRA_SERVICE_START_GENERATION =
            "io.customer.geofence.extra.POLYGON_SERVICE_START_GENERATION"
        const val NOTIFICATION_CHANNEL_ID = "io.customer.geofence.polygon_monitoring"
        const val NOTIFICATION_ID = 0xC10
    }
}

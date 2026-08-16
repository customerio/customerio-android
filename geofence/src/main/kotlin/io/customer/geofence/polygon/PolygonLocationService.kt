package io.customer.geofence.polygon

import android.Manifest
import android.annotation.SuppressLint
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
import io.customer.geofence.di.polygonGeofenceServiceController
import io.customer.geofence.di.polygonLocationEngine
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent

/** Foreground service that keeps polygon evaluation alive while inside a coarse trigger circle. */
internal class PolygonLocationService : Service() {
    private var engineRegistrationGeneration: Long? = null
    private var serviceStartGeneration: Long? = null

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        if (!startForegroundSafely()) {
            stopSelf()
            return
        }
        try {
            SDKComponent.setupAndroidComponent(context = this)
            if (!hasFineLocationPermission()) {
                SDKComponent.geofenceLogger.logPolygonMonitoringFailed("ACCESS_FINE_LOCATION not granted")
                stopSelf()
                return
            }
        } catch (e: RuntimeException) {
            SDKComponent.geofenceLogger.logPolygonMonitoringFailed(e.message)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getLongExtra(EXTRA_SERVICE_START_GENERATION, Long.MIN_VALUE)
            ?.takeIf { it != Long.MIN_VALUE }
            ?.let { generation ->
                serviceStartGeneration = generation
                runCatching {
                    SDKComponent.android().polygonGeofenceServiceController
                        .onServicePromoted(generation)
                }
            }
        try {
            if (!hasFineLocationPermission()) {
                stopSelf()
                return START_STICKY
            }
            engineRegistrationGeneration =
                SDKComponent.android().polygonGeofenceServiceController
                    .startEngineForService(::stopSelf)
            if (engineRegistrationGeneration == null) stopSelf()
        } catch (e: RuntimeException) {
            SDKComponent.geofenceLogger.logPolygonMonitoringFailed(e.message)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        engineRegistrationGeneration?.let { generation ->
            runCatching { SDKComponent.android().polygonLocationEngine.stopIfCurrent(generation) }
        }
        runCatching {
            SDKComponent.android().polygonGeofenceServiceController.onServiceDestroyed(
                serviceStartGeneration
            )
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafely(): Boolean = try {
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
        true
    } catch (e: RuntimeException) {
        false
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

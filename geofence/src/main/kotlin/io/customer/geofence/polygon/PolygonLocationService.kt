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
    private var engineStarted = false

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        if (!startForegroundSafely()) {
            stopSelf()
            return
        }
        try {
            SDKComponent.setupAndroidComponent(context = this)
            val android = SDKComponent.android()
            if (!hasFineLocationPermission()) {
                SDKComponent.geofenceLogger.logPolygonMonitoringFailed("ACCESS_FINE_LOCATION not granted")
                stopSelf()
                return
            }
            engineStarted = android.polygonGeofenceServiceController.startEngineForService(::stopSelf)
            if (!engineStarted) stopSelf()
        } catch (e: RuntimeException) {
            SDKComponent.geofenceLogger.logPolygonMonitoringFailed(e.message)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (engineStarted) {
            runCatching { SDKComponent.android().polygonLocationEngine.stop() }
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

    private companion object {
        const val NOTIFICATION_CHANNEL_ID = "io.customer.geofence.polygon_monitoring"
        const val NOTIFICATION_ID = 0xC10
    }
}

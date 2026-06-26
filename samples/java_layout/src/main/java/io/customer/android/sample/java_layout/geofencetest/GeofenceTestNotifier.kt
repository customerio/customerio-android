package io.customer.android.sample.java_layout.geofencetest

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent

/**
 * Testing-only (geofence-testing branch). Subscribes to [Event.GeofenceTransitionEvent]
 * from the SDK's EventBus and posts a local notification per transition so the
 * tester sees a visible signal on the device without watching logcat. Paired
 * with the SDK-side mock in `GeofenceApiService.kt`.
 */
object GeofenceTestNotifier {

    fun install(app: Application) {
        createChannel(app)
        SDKComponent.eventBus.subscribe(Event.GeofenceTransitionEvent::class) { event ->
            postNotification(app, event)
        }
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Geofence Test Events",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Local notifications for geofence transitions (testing only)."
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun postNotification(context: Context, event: Event.GeofenceTransitionEvent) {
        val direction = when (event.transition) {
            Event.GeofenceTransition.ENTER -> "ENTER"
            Event.GeofenceTransition.EXIT -> "EXIT"
        }
        val name = event.properties["geofenceName"] as? String
        val title = "Geofence $direction" + (name?.let { " · $it" } ?: "")
        // distanceMeters / geofenceRadius are the testing-only props (geofence-testing branch),
        // surfaced here so distance-vs-radius can be eyeballed without the dashboard.
        val distance = event.properties["distanceMeters"]
        val radius = event.properties["geofenceRadius"]
        val text = buildString {
            append("id=${event.geofenceId}")
            if (distance != null && radius != null) append(" · ${distance}m / r=${radius}m")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        // Unique id per (geofence, transition) so back-to-back enters of the
        // same fence don't collapse — tester wants to see every transition.
        val notificationId = (event.geofenceId.hashCode() xor event.transition.ordinal)
            .and(Int.MAX_VALUE)
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId, notification)
    }

    private const val CHANNEL_ID = "geofence_test_events"
}

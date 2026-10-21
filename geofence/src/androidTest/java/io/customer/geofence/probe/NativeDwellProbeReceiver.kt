package io.customer.geofence.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import com.google.android.gms.location.GeofencingEvent
import java.util.concurrent.ConcurrentLinkedQueue

/** One native geofence callback: timing and fix quality only, never coordinates. */
internal data class NativeDwellProbeEvent(
    val id: String,
    val transition: Int,
    val receivedElapsedMs: Long,
    val fixAgeMs: Long?,
    val accuracyMeters: Float?,
    val interactive: Boolean
)

/**
 * Instrumentation-only sink for the dwell-wake probe. Records live in process memory, so a run is
 * valid only while the test process survives; nothing is persisted or logged.
 */
internal class NativeDwellProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val receivedElapsedMs = SystemClock.elapsedRealtime()
        val event = GeofencingEvent.fromIntent(intent) ?: run {
            record(errors, NO_EVENT)
            return
        }
        if (event.hasError()) {
            record(errors, event.errorCode)
            return
        }
        val interactive =
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true
        val fix = event.triggeringLocation
        // Negative ages are dropped: a fix cannot postdate its own delivery.
        val fixAgeMs = fix?.elapsedRealtimeNanos?.takeIf { it > 0L }
            ?.let { receivedElapsedMs - it / NANOS_PER_MILLI }
            ?.takeIf { it >= 0L }
        val accuracyMeters = fix?.takeIf { it.hasAccuracy() }?.accuracy
        event.triggeringGeofences.orEmpty().forEach { geofence ->
            record(
                events,
                NativeDwellProbeEvent(
                    id = geofence.requestId,
                    transition = event.geofenceTransition,
                    receivedElapsedMs = receivedElapsedMs,
                    fixAgeMs = fixAgeMs,
                    accuracyMeters = accuracyMeters,
                    interactive = interactive
                )
            )
        }
    }

    companion object {
        /** GMS error codes, plus [NO_EVENT] for an intent that carried no geofencing event. */
        val errors = ConcurrentLinkedQueue<Int>()
        val events = ConcurrentLinkedQueue<NativeDwellProbeEvent>()
        const val NO_EVENT = -1
        private const val MAX_RECORDS = 256
        private const val NANOS_PER_MILLI = 1_000_000L

        fun clear() {
            errors.clear()
            events.clear()
        }

        private fun <T> record(queue: ConcurrentLinkedQueue<T>, value: T) {
            if (queue.size < MAX_RECORDS) queue.add(value)
        }
    }
}

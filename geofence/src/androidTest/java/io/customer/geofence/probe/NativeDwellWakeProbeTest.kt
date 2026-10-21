package io.customer.geofence.probe

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import io.customer.commontest.core.AndroidTest
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in measurement of whether a same-id GMS re-add restarts the DWELL loiter clock: a control fence
 * keeps 60 s, the variant is re-added in place after 30 s with 120 s. Experimental and never
 * qualification: this live instrumentation process says nothing about SDK cold-process liveness.
 * Requests nothing without `-e cioNativeDwellProbe true`, and logs under [TAG] without coordinates.
 */
@SuppressLint("MissingPermission") // requirePermissions runs before any location request.
@RunWith(AndroidJUnit4::class)
class NativeDwellWakeProbeTest : AndroidTest() {
    private enum class Outcome { RESET_REFUTED, CONSISTENT_WITH_RESET, INCONCLUSIVE }

    private data class Verdict(val outcome: Outcome, val reason: String, val decided: Boolean)

    private var logged = 0

    @Test(timeout = 650_000L)
    fun sameIdReAdd_givenOptIn_classifiesVariantDwellAgainstResetBound() {
        assumeTrue("opt-in only", InstrumentationRegistry.getArguments().getString(ARG) == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        requirePermissions(context)
        val setup = deviceState(context)
        val fix = currentFix(context)
        val quality = quality(fix)
        val geofencing = LocationServices.getGeofencingClient(context)
        val intent = probeIntent(context)
        var addAttempted = false
        var cleanupError: Throwable? = null
        val (verdict, summary) = try {
            NativeDwellProbeReceiver.clear()
            val start = SystemClock.elapsedRealtime()
            addAttempted = true
            val first = request(fence(CONTROL, fix, FIRST_LOITER_MS), fence(VARIANT, fix, FIRST_LOITER_MS))
            await(geofencing.addGeofences(first, intent))
            poll(start, start + READD_AFTER_MS) { false }
            // Bounded from the call's start: GMS can apply the re-add later than that, never earlier.
            val readdStart = SystemClock.elapsedRealtime()
            await(geofencing.addGeofences(request(fence(VARIANT, fix, READD_LOITER_MS)), intent))
            val readdDone = SystemClock.elapsedRealtime()
            val bound = readdStart + READD_LOITER_MS
            poll(start, start + OBSERVE_MS) { classify(start, readdStart, bound).decided }
            val result = classify(start, readdStart, bound)
            val events = NativeDwellProbeReceiver.events.toList().take(MAX_REPORTED)
                .joinToString("; ") { describe(it, start) }
            result to "result=${result.outcome} reason=${result.reason} radius=${RADIUS_METERS}m " +
                "loiter=${FIRST_LOITER_MS}ms then ${READD_LOITER_MS}ms readd=+${readdStart - start}" +
                "..+${readdDone - start}ms bound=+${bound - start}ms $setup $quality events=[$events] " +
                "errors=${NativeDwellProbeReceiver.errors.toList().take(MAX_REPORTED)} " +
                "(live instrumentation process; not SDK liveness or qualification)"
        } finally {
            val interrupted = Thread.interrupted() // A timeout's interrupt must not skip removal.
            if (addAttempted) {
                cleanupError = runCatching {
                    await(geofencing.removeGeofences(listOf(CONTROL, VARIANT)))
                }.exceptionOrNull()
            }
            intent.cancel()
            if (interrupted) Thread.currentThread().interrupt()
        }
        Log.i(TAG, summary)
        assertTrue("GMS geofencing errors: $summary", NativeDwellProbeReceiver.errors.isEmpty())
        assertNull("probe cleanup failed: $summary", cleanupError)
        when (verdict.outcome) {
            Outcome.RESET_REFUTED -> fail("experimental probe, not a production assertion: $summary")
            Outcome.INCONCLUSIVE -> assumeTrue("inconclusive, reported as skipped: $summary", false)
            Outcome.CONSISTENT_WITH_RESET -> Unit
        }
    }

    /** A late variant is consistent with a reset; an early one refutes it if GMS honors the delay. */
    private fun classify(start: Long, readdStart: Long, bound: Long): Verdict {
        val events = NativeDwellProbeReceiver.events.toList()
        val variant = events.firstOrNull { it.id == VARIANT && it.transition == DWELL }
        val control = events.firstOrNull { it.id == CONTROL && it.transition == DWELL }
        // Over-estimated from the add call's start, which keeps a CONSISTENT verdict conservative.
        val controlLag = control?.let { maxOf(0L, it.receivedElapsedMs - start - FIRST_LOITER_MS) }
        return when {
            variant != null && variant.receivedElapsedMs < readdStart ->
                Verdict(Outcome.INCONCLUSIVE, "variant DWELL before the re-add", true)
            variant != null && variant.receivedElapsedMs < bound ->
                Verdict(Outcome.RESET_REFUTED, "variant DWELL before re-add start + 120 s", true)
            events.any { it.transition == Geofence.GEOFENCE_TRANSITION_EXIT } ->
                Verdict(Outcome.INCONCLUSIVE, "an EXIT arrived; the device may have left", true)
            variant == null || controlLag == null ->
                Verdict(Outcome.INCONCLUSIVE, "missing control or variant DWELL", false)
            variant.receivedElapsedMs - controlLag < bound ->
                Verdict(Outcome.INCONCLUSIVE, "variant lateness within the control's delivery lag", true)
            else -> Verdict(Outcome.CONSISTENT_WITH_RESET, "variant late beyond control lag; not proof", true)
        }
    }

    private fun poll(start: Long, until: Long, done: () -> Boolean) {
        var lastStatus: Long? = null
        while (true) {
            val events = NativeDwellProbeReceiver.events.toList()
            events.drop(logged).forEach { Log.i(TAG, "event " + describe(it, start)) }
            logged = events.size
            val now = SystemClock.elapsedRealtime()
            if (now >= until || done()) return
            if (lastStatus == null || now - lastStatus >= STATUS_EVERY_MS) {
                val errors = NativeDwellProbeReceiver.errors.size
                Log.i(TAG, "status +${now - start}ms events=${events.size} errors=$errors")
                lastStatus = now
            }
            Thread.sleep(minOf(POLL_MS, until - now))
        }
    }

    private fun describe(event: NativeDwellProbeEvent, start: Long): String =
        "${event.id.removePrefix(PREFIX)}/${event.transition}@+${event.receivedElapsedMs - start}ms " +
            "interactive=${event.interactive} fixAgeMs=${event.fixAgeMs} accM=${event.accuracyMeters}"

    private fun requirePermissions(context: Context) {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) needed += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        val missing = needed.filter {
            context.checkPermission(it, Process.myPid(), Process.myUid()) != PackageManager.PERMISSION_GRANTED
        }
        // Grants nothing: granting is the operator's step, scoped to this test APK.
        assertTrue("prerequisite: grant $missing to the test APK", missing.isEmpty())
    }

    private fun deviceState(context: Context): String {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val idle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) power.isDeviceIdleMode else null
        return "sdk=${Build.VERSION.SDK_INT} setupInteractive=${power.isInteractive} " +
            "idle=$idle powerSave=${power.isPowerSaveMode}"
    }

    private fun currentFix(context: Context): Location {
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0L)
            .setDurationMillis(FIX_DURATION_MS)
            .build()
        val token = CancellationTokenSource()
        val fix = try {
            val task = LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request, token.token)
            Tasks.await(task, FIX_AWAIT_S, TimeUnit.SECONDS)
        } finally {
            token.cancel()
        }
        return fix?.takeIf { usable(it) }
            ?: throw AssertionError("prerequisite: no fresh precise fix (accuracy <= 500 m, age <= 30 s)")
    }

    private fun usable(fix: Location): Boolean {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / NANOS_PER_MS
        return fix.latitude in -90.0..90.0 && fix.longitude in -180.0..180.0 && fix.hasAccuracy() &&
            fix.accuracy.isFinite() && fix.accuracy > 0f && fix.accuracy <= MAX_ACCURACY_METERS &&
            fix.elapsedRealtimeNanos > 0L && ageMs in 0L..FIX_MAX_AGE_MS
    }

    private fun quality(fix: Location): String {
        @Suppress("DEPRECATION")
        val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) fix.isMock else fix.isFromMockProvider
        val ageMs = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / NANOS_PER_MS
        return "fixAccM=${fix.accuracy} fixAgeMs=$ageMs mock=$mock provider=${fix.provider}"
    }

    private fun probeIntent(context: Context): PendingIntent {
        // Mutable: GMS fills the geofencing extras in.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(context, NativeDwellProbeReceiver::class.java)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    private fun fence(id: String, fix: Location, loiteringMs: Int): Geofence = Geofence.Builder()
        .setRequestId(id)
        .setCircularRegion(fix.latitude, fix.longitude, RADIUS_METERS)
        .setExpirationDuration(EXPIRATION_MS)
        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT or DWELL)
        .setLoiteringDelay(loiteringMs)
        .setNotificationResponsiveness(0)
        .build()

    private fun request(vararg fences: Geofence): GeofencingRequest = GeofencingRequest.Builder()
        .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_DWELL)
        .addGeofences(fences.toList())
        .build()

    private fun <T> await(task: Task<T>): T = Tasks.await(task, GMS_AWAIT_S, TimeUnit.SECONDS)

    private companion object {
        const val TAG = "NativeDwellProbe"
        const val ARG = "cioNativeDwellProbe"
        const val PREFIX = "cio-dwell-"
        const val CONTROL = PREFIX + "control"
        const val VARIANT = PREFIX + "variant"
        const val DWELL = Geofence.GEOFENCE_TRANSITION_DWELL
        const val REQUEST_CODE = 0x0D3E
        const val RADIUS_METERS = 1_000f
        const val FIRST_LOITER_MS = 60_000
        const val READD_LOITER_MS = 120_000
        const val READD_AFTER_MS = 30_000L
        const val OBSERVE_MS = 8 * 60_000L
        const val EXPIRATION_MS = 10 * 60_000L
        const val FIX_DURATION_MS = 30_000L
        const val FIX_MAX_AGE_MS = 30_000L
        const val MAX_ACCURACY_METERS = 500f
        const val FIX_AWAIT_S = 35L
        const val GMS_AWAIT_S = 30L
        const val POLL_MS = 5_000L
        const val STATUS_EVERY_MS = 30_000L
        const val MAX_REPORTED = 20
        const val NANOS_PER_MS = 1_000_000L
    }
}

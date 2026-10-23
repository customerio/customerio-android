package io.customer.geofence.polygon

import android.annotation.SuppressLint
import android.location.Location
import android.os.SystemClock
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import io.customer.geofence.DwellDiagnosticEvent
import io.customer.geofence.DwellDiagnosticReason
import io.customer.geofence.DwellDiagnosticRecord
import io.customer.geofence.DwellDiagnosticRecorder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [BALANCED] suffices for a wake circle hundreds of metres across. [HIGH_ACCURACY] is what a ring
 * needs and costs GPS, so ask for it only once a cheap fix shows it could matter.
 */
internal enum class PolygonFixPriority {
    BALANCED,
    HIGH_ACCURACY
}

/** About accuracy, not age: a callback's fix is already recent but can be too coarse for an edge. */
internal interface PolygonFreshFixSource {
    /**
     * A fix no older than this call, or null within [timeoutMs]. Never throws: missing permission,
     * a disabled provider and a timeout all mean "decide on what you already have".
     */
    suspend fun awaitFreshFix(
        timeoutMs: Long,
        priority: PolygonFixPriority = PolygonFixPriority.HIGH_ACCURACY
    ): Location?
}

internal class GmsPolygonFreshFixSource(
    private val client: FusedLocationProviderClient,
    /** Null records nothing. */
    private val diagnostics: DwellDiagnosticRecorder? = null
) : PolygonFreshFixSource {
    /** Not `@RequiresPermission`: permission can be revoked after any check, so it's handled as null. */
    @SuppressLint("MissingPermission")
    override suspend fun awaitFreshFix(timeoutMs: Long, priority: PolygonFixPriority): Location? {
        if (timeoutMs <= 0L) return null
        // Before the request: an answer landing after an identity reset carries a retired token,
        // so it is dropped rather than written into the next user's journal.
        val diagnosticToken = diagnostics?.currentToken()
        recordDiagnostic(diagnosticToken) {
            DwellDiagnosticRecord(
                event = DwellDiagnosticEvent.FIX_REQUESTED,
                atElapsedMs = SystemClock.elapsedRealtime(),
                priority = priority.name
            )
        }
        val tokenSource = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(
                when (priority) {
                    PolygonFixPriority.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
                    PolygonFixPriority.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
                }
            )
            // No cached fix: a cached one is the fix we already failed to decide on.
            .setMaxUpdateAgeMillis(0L)
            .setDurationMillis(timeoutMs)
            .build()
        val fix: Location? = try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { tokenSource.cancel() }
                    client.getCurrentLocation(request, tokenSource.token)
                        .addOnSuccessListener { location ->
                            if (continuation.isActive) continuation.resumeWith(Result.success(location))
                        }
                        .addOnFailureListener {
                            if (continuation.isActive) continuation.resumeWith(Result.success(null))
                        }
                }
            }
        } catch (e: SecurityException) {
            null
        } finally {
            // Also on success: cancelling stops GMS holding the sensor open past the answer.
            tokenSource.cancel()
        }
        // Not reached when the caller is cancelled: that propagates unchanged and unrecorded.
        recordDiagnostic(diagnosticToken) { receivedRecord(fix, priority) }
        return fix
    }

    private inline fun recordDiagnostic(token: Long?, record: () -> DwellDiagnosticRecord) {
        if (token != null) diagnostics?.record(record(), token)
    }

    /** Fix quality only, never coordinates. NONE_ARRIVED covers a timeout, a failure and a refusal. */
    private fun receivedRecord(fix: Location?, priority: PolygonFixPriority): DwellDiagnosticRecord {
        val nowMs = SystemClock.elapsedRealtime()
        val received = DwellDiagnosticRecord(
            event = DwellDiagnosticEvent.FIX_RECEIVED,
            atElapsedMs = nowMs,
            priority = priority.name
        )
        if (fix == null) return received.copy(reason = DwellDiagnosticReason.NONE_ARRIVED)
        val fixElapsedNanos = fix.elapsedRealtimeNanos.takeIf { it > 0L }
        return received.copy(
            provider = fix.provider,
            returnedAccuracyMeters = if (fix.hasAccuracy()) fix.accuracy.toDouble() else null,
            fixAgeMs = fixElapsedNanos?.let { nowMs - it / NANOS_PER_MILLI }?.takeIf { it >= 0L },
            fixElapsedNanos = fixElapsedNanos
        )
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

package io.customer.geofence

import io.customer.geofence.store.GeofenceDwellVisit
import kotlin.math.abs

/**
 * Wall time can step (network time, a manual change), so visits are ordered and
 * measured on elapsedRealtime, which is monotonic within one boot. Wall stamps are only reported, and
 * only while they still agree with it.
 */
internal object GeofenceVisitTiming {
    /**
     * How far the wall-clock and elapsed spans of one visit may differ and still be reported together.
     * Covers rounding only: each wall stamp is truncated to whole seconds, so their span is off by
     * under 1 s, plus the moment between reading the two clocks. Anything larger is a clock step.
     */
    const val MAX_WALL_CLOCK_DISAGREEMENT_MS = 2_000L

    /**
     * Milliseconds the visit had lasted at [atElapsedMs], or null when its start is unknown: a visit
     * that predates timing provenance, or one stamped in another boot.
     */
    fun elapsedMs(visit: GeofenceDwellVisit, bootSessionId: String, atElapsedMs: Long): Long? {
        if (visit.bootSessionId != bootSessionId) return null
        val startedAt = visit.enteredAtElapsedMs ?: return null
        return (atElapsedMs - startedAt).takeIf { it >= 0L }
    }

    /**
     * Whether the wall stamps span the same time as [elapsedMs]. When they don't, the clock stepped
     * somewhere in between and the wall entry time can't be paired with the elapsed duration.
     */
    fun wallClockAgrees(enteredAtSeconds: Long, observedAtSeconds: Long, elapsedMs: Long): Boolean =
        abs((observedAtSeconds - enteredAtSeconds) * MILLIS_PER_SECOND - elapsedMs) <=
            MAX_WALL_CLOCK_DISAGREEMENT_MS

    /**
     * Whether [visit] was stamped in another boot, so none of its elapsed-realtime fields apply now.
     * Compares boot identity, not uptime: a new boot can run longer than the old one before a missed
     * BOOT_COMPLETED is noticed.
     */
    fun isFromAnotherBoot(visit: GeofenceDwellVisit, bootSessionId: String): Boolean =
        visit.bootSessionId != null && visit.bootSessionId != bootSessionId

    private const val MILLIS_PER_SECOND = 1_000L
}

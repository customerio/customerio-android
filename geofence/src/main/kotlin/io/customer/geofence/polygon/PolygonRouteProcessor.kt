package io.customer.geofence.polygon

import io.customer.geofence.PolygonArrivalCommit
import io.customer.geofence.PolygonArrivalExpiry

internal data class PolygonFence(
    val id: String,
    val geometry: PolygonGeometry,
    val regionRevision: Int = 0
) {
    init {
        require(id.isNotBlank()) { "polygon id cannot be blank" }
    }
}

internal data class PolygonTransitionDetection(
    val polygonId: String,
    val transition: PolygonTransition,
    val regionRevision: Int = 0
)

/**
 * Returned rather than logged: every caller of `process` holds a lock, and the host's log
 * dispatcher is customer code that could stall evaluation.
 */
internal sealed interface PolygonRouteRecord {
    val geofenceId: String

    data class Undecided(
        override val geofenceId: String,
        val reason: PolygonUndecidedReason,
        val signedBoundaryDistanceMeters: Double?,
        val horizontalAccuracyMeters: Double,
        val fixAgeSeconds: Double
    ) : PolygonRouteRecord

    data class Unchanged(
        override val geofenceId: String,
        val membership: String,
        val signedBoundaryDistanceMeters: Double?,
        val horizontalAccuracyMeters: Double,
        val fixAgeSeconds: Double
    ) : PolygonRouteRecord

    data class Decided(
        override val geofenceId: String,
        val transitionName: String,
        val signedBoundaryDistanceMeters: Double?,
        val horizontalAccuracyMeters: Double,
        val fixAgeSeconds: Double,
        val corroborated: Boolean,
        /** Set only on an arrival committed without a second agreeing fix. */
        val uncorroboratedReason: PolygonArrivalCommit? = null
    ) : PolygonRouteRecord

    data class ArrivalPending(
        override val geofenceId: String,
        val signedBoundaryDistanceMeters: Double?,
        val horizontalAccuracyMeters: Double?,
        val fixAgeSeconds: Double?
    ) : PolygonRouteRecord

    data class ArrivalExpired(
        override val geofenceId: String,
        val reason: PolygonArrivalExpiry,
        val heldForSeconds: Double? = null
    ) : PolygonRouteRecord
}

internal sealed interface HeldArrivalOutcome {
    /**
     * The `held*` fields describe the fix the arrival rests on, not the one that released it, and
     * [heldFixAgeSeconds] includes the wait. [reason] is null when the fix positively agreed.
     */
    data class Committed(
        val reason: PolygonArrivalCommit?,
        val heldSignedBoundaryDistanceMeters: Double?,
        val heldHorizontalAccuracyMeters: Double,
        val heldFixAgeSeconds: Double
    ) : HeldArrivalOutcome

    data object NotCommitted : HeldArrivalOutcome
}

internal data class PolygonRouteOutcome(
    val detections: List<PolygonTransitionDetection>,
    val records: List<PolygonRouteRecord>
)

internal class PolygonRouteProcessor(
    private val accuracyEvaluator: PolygonAccuracyEvaluator = PolygonAccuracyEvaluator(),
    /**
     * Two confirmations (the minimum), so opening a hold yields no transition and
     * [resolveHeldArrival] decides what the next fix means.
     */
    private val arrivalConfirmations: PolygonTransitionStateMachine =
        PolygonTransitionStateMachine(requiredConfirmations = 2)
) {
    private val latestElapsedRealtimeNanos = mutableMapOf<String, Long>()

    /** [resolveHeldArrival] drops an entry with no pending hold, so a missed removal costs one pass. */
    private val heldArrivals = mutableMapOf<String, HeldArrival>()

    private data class HeldArrival(
        val sample: PolygonLocationSample,
        val signedBoundaryDistanceMeters: Double?,
        val fixAgeSeconds: Double,
        val observedAtElapsedRealtimeNanos: Long
    )

    fun process(
        fences: List<PolygonFence>,
        sample: PolygonLocationSample,
        elapsedRealtimeNanos: Long,
        fixAgeSeconds: Double,
        committedStates: Map<String, PolygonCommittedState>,
        answersHeldFixAt: Long? = null
    ): PolygonRouteOutcome {
        require(elapsedRealtimeNanos >= 0L) { "elapsed realtime must be non-negative" }
        require(fences.map(PolygonFence::id).distinct().size == fences.size) {
            "polygon ids must be unique"
        }

        val activeIds = fences.mapTo(mutableSetOf(), PolygonFence::id)
        trackedFenceIds.filterNot(activeIds::contains).forEach { retired ->
            clearHold(retired)
        }
        latestElapsedRealtimeNanos.keys.retainAll(activeIds)
        heldArrivals.keys.retainAll(activeIds)
        trackedFenceIds.clear()
        trackedFenceIds.addAll(activeIds)

        val records = mutableListOf<PolygonRouteRecord>()
        val detections = fences.mapNotNull { fence ->
            val latest = latestElapsedRealtimeNanos[fence.id]
            val answersHold = answersHeldFixAt == elapsedRealtimeNanos && isHeldFix(fence.id, elapsedRealtimeNanos)
            if (latest != null && elapsedRealtimeNanos <= latest && !answersHold) return@mapNotNull null
            latestElapsedRealtimeNanos[fence.id] = elapsedRealtimeNanos
            val committedState = committedStates[fence.id] ?: PolygonCommittedState.OUTSIDE
            val result = accuracyEvaluator.decisiveEvidenceFor(fence.geometry, sample, committedState)
            val evidence = result.evidence
            result.undecidedReason?.let { reason ->
                records += PolygonRouteRecord.Undecided(
                    geofenceId = fence.id,
                    reason = reason,
                    signedBoundaryDistanceMeters = result.signedBoundaryDistanceMeters,
                    horizontalAccuracyMeters = sample.horizontalAccuracyMeters,
                    fixAgeSeconds = fixAgeSeconds
                )
            }
            if (result.agreedWithCommittedState) {
                records += PolygonRouteRecord.Unchanged(
                    geofenceId = fence.id,
                    membership = committedState.name,
                    signedBoundaryDistanceMeters = result.signedBoundaryDistanceMeters,
                    horizontalAccuracyMeters = sample.horizontalAccuracyMeters,
                    fixAgeSeconds = fixAgeSeconds
                )
            }
            // Settle a held arrival before judging this fix.
            when (
                val held = resolveHeldArrival(
                    fence.id,
                    result,
                    sample,
                    elapsedRealtimeNanos,
                    records
                )
            ) {
                is HeldArrivalOutcome.Committed -> {
                    records += PolygonRouteRecord.Decided(
                        geofenceId = fence.id,
                        transitionName = PolygonTransition.ENTER.name,
                        signedBoundaryDistanceMeters = held.heldSignedBoundaryDistanceMeters,
                        horizontalAccuracyMeters = held.heldHorizontalAccuracyMeters,
                        fixAgeSeconds = held.heldFixAgeSeconds,
                        corroborated = held.reason == null,
                        uncorroboratedReason = held.reason
                    )
                    return@mapNotNull PolygonTransitionDetection(
                        fence.id,
                        PolygonTransition.ENTER,
                        fence.regionRevision
                    )
                }
                // Judge the fix normally. A stale hold's replacement arrival is this fix's to open,
                // and a fix that broke a hold may still be a departure in its own right.
                HeldArrivalOutcome.NotCommitted -> Unit
            }
            val isTransitionEvidence =
                committedState == PolygonCommittedState.OUTSIDE && evidence == PolygonEvidence.ENTER ||
                    committedState == PolygonCommittedState.INSIDE && evidence == PolygonEvidence.EXIT
            if (!isTransitionEvidence) return@mapNotNull null
            val transition = when (evidence) {
                // A fix whose uncertainty does not reach the ring decides alone: a passer-by on the
                // pavement cannot produce one.
                PolygonEvidence.ENTER -> when {
                    !result.requiresCorroboration -> {
                        clearHold(fence.id)
                        PolygonTransition.ENTER
                    }
                    else -> {
                        // Any open hold was settled above, so this always opens a new one.
                        openHold(
                            polygonId = fence.id,
                            committedState = committedState,
                            evidence = evidence,
                            elapsedRealtimeNanos = elapsedRealtimeNanos,
                            sample = sample,
                            result = result,
                            fixAgeSeconds = fixAgeSeconds
                        )
                        records += PolygonRouteRecord.ArrivalPending(
                            geofenceId = fence.id,
                            signedBoundaryDistanceMeters = result.signedBoundaryDistanceMeters,
                            horizontalAccuracyMeters = sample.horizontalAccuracyMeters,
                            fixAgeSeconds = fixAgeSeconds
                        )
                        return@mapNotNull null
                    }
                }
                // Departure keeps its clearance margin, so it needs no second opinion, and delaying
                // it would report a visit as still running after it ended.
                PolygonEvidence.EXIT -> {
                    clearHold(fence.id)
                    PolygonTransition.EXIT
                }
                PolygonEvidence.AMBIGUOUS -> return@mapNotNull null
            }
            records += PolygonRouteRecord.Decided(
                geofenceId = fence.id,
                transitionName = transition.name,
                signedBoundaryDistanceMeters = result.signedBoundaryDistanceMeters,
                horizontalAccuracyMeters = sample.horizontalAccuracyMeters,
                fixAgeSeconds = fixAgeSeconds,
                corroborated = false
            )
            PolygonTransitionDetection(fence.id, transition, fence.regionRevision)
        }
        return PolygonRouteOutcome(detections, records)
    }

    /** Returns the ids that were still holding an arrival. */
    fun clear(): Set<String> {
        val discarded = arrivalConfirmations.pendingPolygonIds()
        latestElapsedRealtimeNanos.clear()
        trackedFenceIds.clear()
        heldArrivals.clear()
        arrivalConfirmations.clearAll()
        return discarded
    }

    /** True when the fence was still holding an arrival that this discarded. */
    fun clear(polygonId: String): Boolean {
        val wasPending = arrivalConfirmations.hasPending(polygonId)
        trackedFenceIds.remove(polygonId)
        latestElapsedRealtimeNanos.remove(polygonId)
        heldArrivals.remove(polygonId)
        arrivalConfirmations.clear(polygonId)
        return wasPending
    }

    /**
     * Commits unless a judged fix places the device outside: a second agreeing fix is unsatisfiable
     * for a stationary device, whose provider re-emits the same coordinate. Committed state is not
     * rechecked, since every ENTER the owning engine decides clears the hold.
     */
    private fun resolveHeldArrival(
        polygonId: String,
        result: PolygonEvidenceResult,
        sample: PolygonLocationSample,
        elapsedRealtimeNanos: Long,
        records: MutableList<PolygonRouteRecord>
    ): HeldArrivalOutcome {
        val held = heldArrivals[polygonId]
        if (!arrivalConfirmations.hasPending(polygonId) || held == null) {
            clearHold(polygonId)
            return HeldArrivalOutcome.NotCommitted
        }
        // Before the window, so a contradiction means the same on either side of it. Not the
        // departure rule: its clearance margin would let a fix just outside commit a false visit.
        val judged = result.undecidedReason == null
        val readsOutside = (result.signedBoundaryDistanceMeters ?: 0.0) < 0.0
        if (judged && readsOutside) {
            clearHold(polygonId)
            records += PolygonRouteRecord.ArrivalExpired(
                geofenceId = polygonId,
                reason = PolygonArrivalExpiry.EVIDENCE_BROKEN
            )
            return HeldArrivalOutcome.NotCommitted
        }
        val heldForNanos = elapsedRealtimeNanos - held.observedAtElapsedRealtimeNanos
        // Past the window the hold is stale: the one branch that loses a visit, reached only if
        // the requested precise fix never came.
        if (heldForNanos > MAX_CORROBORATION_GAP_NANOS) {
            clearHold(polygonId)
            records += PolygonRouteRecord.ArrivalExpired(
                geofenceId = polygonId,
                reason = PolygonArrivalExpiry.WINDOW_ELAPSED,
                heldForSeconds = heldForNanos.toDouble() / NANOS_PER_SECOND
            )
            return HeldArrivalOutcome.NotCommitted
        }
        // Position alone: a provider re-emission keeps the coordinate but replaces the accuracy.
        val reason = when {
            held.sample.coordinate == sample.coordinate -> PolygonArrivalCommit.NOT_INDEPENDENT
            result.evidence == PolygonEvidence.ENTER -> null
            else -> PolygonArrivalCommit.UNJUDGEABLE
        }
        clearHold(polygonId)
        return HeldArrivalOutcome.Committed(
            reason = reason,
            heldSignedBoundaryDistanceMeters = held.signedBoundaryDistanceMeters,
            heldHorizontalAccuracyMeters = held.sample.horizontalAccuracyMeters,
            // The monotonic gap is the only clock both ends of this can trust.
            heldFixAgeSeconds = held.fixAgeSeconds + heldForNanos.toDouble() / NANOS_PER_SECOND
        )
    }

    /**
     * GMS can answer the hold's precise-fix request with the held fix itself, even at max update age
     * 0; marked via `answersHeldFixAt`, it settles the hold instead of being skipped as not newer.
     * The same fix by any other path is still refused.
     */
    private fun isHeldFix(polygonId: String, elapsedRealtimeNanos: Long): Boolean =
        heldArrivals[polygonId]?.observedAtElapsedRealtimeNanos == elapsedRealtimeNanos

    private fun openHold(
        polygonId: String,
        committedState: PolygonCommittedState,
        evidence: PolygonEvidence,
        elapsedRealtimeNanos: Long,
        sample: PolygonLocationSample,
        result: PolygonEvidenceResult,
        fixAgeSeconds: Double
    ) {
        arrivalConfirmations.evaluate(polygonId, committedState, evidence)
        heldArrivals[polygonId] = HeldArrival(
            sample = sample,
            signedBoundaryDistanceMeters = result.signedBoundaryDistanceMeters,
            fixAgeSeconds = fixAgeSeconds,
            observedAtElapsedRealtimeNanos = elapsedRealtimeNanos
        )
    }

    private fun clearHold(polygonId: String) {
        arrivalConfirmations.clear(polygonId)
        heldArrivals.remove(polygonId)
    }

    private val trackedFenceIds = mutableSetOf<String>()

    private companion object {
        /**
         * How far apart two marginal fixes may be and still describe one visit: a few missed
         * deliveries, but not a separate pass at the same shop.
         */
        const val MAX_CORROBORATION_GAP_NANOS = 60_000_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

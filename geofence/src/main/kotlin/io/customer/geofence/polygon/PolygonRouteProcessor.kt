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
 * A record decided during [PolygonRouteProcessor.process] and emitted by the caller.
 *
 * Returned rather than logged because every caller of `process` holds a lock, and `GeofenceLogger`
 * forwards to the host `Logger`, whose customer-supplied dispatcher could stall evaluation.
 */
internal sealed interface PolygonRouteRecord {
    val geofenceId: String

    /** Evaluated, but the fix does not separate inside from outside. */
    data class Undecided(
        override val geofenceId: String,
        val reason: PolygonUndecidedReason,
        val signedBoundaryDistanceMeters: Double?,
        val horizontalAccuracyMeters: Double,
        val fixAgeSeconds: Double
    ) : PolygonRouteRecord

    /** Decisive, and it agrees with what is already committed. */
    data class Unchanged(
        override val geofenceId: String,
        val membership: String,
        val signedBoundaryDistanceMeters: Double?,
        val horizontalAccuracyMeters: Double,
        val fixAgeSeconds: Double
    ) : PolygonRouteRecord

    /** A transition this pass is claiming. */
    data class Decided(
        override val geofenceId: String,
        val transitionName: String,
        val signedBoundaryDistanceMeters: Double?,
        val horizontalAccuracyMeters: Double,
        val fixAgeSeconds: Double,
        val corroborated: Boolean,
        /** Set only on an arrival reported without a second fix agreeing, and says which case. */
        val uncorroboratedReason: PolygonArrivalCommit? = null
    ) : PolygonRouteRecord

    /** Decided ENTER, held for a second agreeing fix. */
    data class ArrivalPending(
        override val geofenceId: String,
        val signedBoundaryDistanceMeters: Double?,
        val horizontalAccuracyMeters: Double?,
        val fixAgeSeconds: Double?
    ) : PolygonRouteRecord

    /** A hold that ended without becoming an arrival. */
    data class ArrivalExpired(
        override val geofenceId: String,
        val reason: PolygonArrivalExpiry,
        val heldForSeconds: Double? = null
    ) : PolygonRouteRecord
}

/** How a held arrival was settled by the next fix to reach its fence. */
internal sealed interface HeldArrivalOutcome {
    /**
     * Reported now. [reason] is null when the fix positively agreed, set when nothing did.
     *
     * The `held*` fields describe the fix the arrival rests on, not the later fix that released it.
     * [heldFixAgeSeconds] is that fix's age when the verdict is emitted, so it includes the wait.
     */
    data class Committed(
        val reason: PolygonArrivalCommit?,
        val heldSignedBoundaryDistanceMeters: Double?,
        val heldHorizontalAccuracyMeters: Double,
        val heldFixAgeSeconds: Double
    ) : HeldArrivalOutcome

    /**
     * No arrival to report from the hold: none was open, the fix contradicted it, or it went stale.
     * The caller judges the fix on its own merits in every case, since it may still be a departure.
     */
    data object NotCommitted : HeldArrivalOutcome
}

/** What one pass over the active polygons decided, and what it owes the log. */
internal data class PolygonRouteOutcome(
    val detections: List<PolygonTransitionDetection>,
    val records: List<PolygonRouteRecord>
)

/** Evaluates one ordered location stream against the currently active polygons. */
internal class PolygonRouteProcessor(
    private val accuracyEvaluator: PolygonAccuracyEvaluator = PolygonAccuracyEvaluator(),
    /**
     * Marks which fences are holding a marginal arrival. Two confirmations (the minimum), so opening
     * a hold yields no transition and [resolveHeldArrival] decides what the next fix means.
     */
    private val arrivalConfirmations: PolygonTransitionStateMachine =
        PolygonTransitionStateMachine(requiredConfirmations = 2)
) {
    private val latestElapsedRealtimeNanos = mutableMapOf<String, Long>()

    /**
     * The fix each held arrival rests on and when the hold opened, so the committed verdict reports
     * that fix rather than the one that released it. [resolveHeldArrival] drops the entry whenever
     * no hold is pending, so a missed removal costs one pass rather than a wrong verdict.
     */
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
            // Settle a held arrival first. It commits unless this fix positively places the device
            // outside; a fix that cannot judge does not contradict it.
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
                        // The held fix, not this one: the arrival rests on the fix that decided it.
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
                // A fix whose uncertainty does not reach the ring decides on its own: a passer-by
                // on the pavement cannot produce one. A marginal fix opens a hold instead, and the
                // next fix to reach this fence settles it above.
                PolygonEvidence.ENTER -> when {
                    !result.requiresCorroboration -> {
                        clearHold(fence.id)
                        PolygonTransition.ENTER
                    }
                    else -> {
                        // Any hold that was open has already been settled, so reaching here always
                        // opens a new one.
                        openHold(
                            polygonId = fence.id,
                            committedState = committedState,
                            evidence = evidence,
                            elapsedRealtimeNanos = elapsedRealtimeNanos,
                            sample = sample,
                            result = result,
                            fixAgeSeconds = fixAgeSeconds
                        )
                        // Without this record the branch consumes a fix and emits nothing, which is
                        // indistinguishable in a capture from a callback that never arrived.
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

    /**
     * Returns the ids that were still holding an arrival, for the caller to report outside its lock
     * (the host's log dispatcher is customer code).
     */
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
     * Settles a held arrival against the next fix to reach its fence.
     *
     * A marginal arrival commits unless a judged fix places the device outside (the iOS rule).
     * Requiring a second agreeing fix is unsatisfiable for a stationary device: the fused provider
     * re-emits the same coordinate, which is not an independent observation.
     *
     * A hold is only open while the fence is committed outside, so that is not rechecked: polygons
     * are committed inside only by the engine that owns this processor, and every ENTER it decides
     * clears the hold. The fix is judged before the staleness window so a contradicting fix means
     * the same on either side of that boundary.
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
        // Any judged fix reading outside blocks the arrival. Not the departure rule: its clearance
        // margin would let a fix 8 m outside at 15 m accuracy commit a false visit.
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
        // Past the window the hold no longer describes where the device is, so it is dropped: the
        // one branch here that loses a visit, reached only if the requested precise fix never came.
        if (heldForNanos > MAX_CORROBORATION_GAP_NANOS) {
            clearHold(polygonId)
            records += PolygonRouteRecord.ArrivalExpired(
                geofenceId = polygonId,
                reason = PolygonArrivalExpiry.WINDOW_ELAPSED,
                heldForSeconds = heldForNanos.toDouble() / NANOS_PER_SECOND
            )
            return HeldArrivalOutcome.NotCommitted
        }
        // Position alone: the provider's re-emission keeps the coordinate but replaces the accuracy
        // with a placeholder.
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
            // Its age when it was taken, plus what the hold has waited since. The monotonic gap is
            // the only clock either end of this can trust.
            heldFixAgeSeconds = held.fixAgeSeconds + heldForNanos.toDouble() / NANOS_PER_SECOND
        )
    }

    /**
     * Whether [elapsedRealtimeNanos] is the fix this fence's hold was opened on.
     *
     * GMS can answer the hold's precise-fix request with that same fix, even at max update age 0.
     * Skipping it as not-newer would leave the hold waiting for a fix minutes away, so that answer
     * (marked by the caller via `answersHeldFixAt`) settles the hold as not independent. The same fix
     * by any other path is still refused, so it cannot commit ahead of a newer outside reading.
     */
    private fun isHeldFix(polygonId: String, elapsedRealtimeNanos: Long): Boolean =
        heldArrivals[polygonId]?.observedAtElapsedRealtimeNanos == elapsedRealtimeNanos

    /** Opens a hold on a marginal arrival, recording the fix and the moment it is counted from. */
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
         * How far apart two marginal fixes may be and still describe one visit. The approach session
         * samples every 15 s, so a corroborating fix is normally the next one; this allows a few
         * missed deliveries without letting a separate pass at the same shop complete an arrival.
         * Bounds the age of the evidence, not how much of it is required.
         */
        const val MAX_CORROBORATION_GAP_NANOS = 60_000_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

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
 * Returned rather than logged because every caller of `process` holds a lock, and
 * `GeofenceLogger` forwards to the host `Logger`, whose dispatcher is customer-supplied code. A
 * slow dispatcher would then stall polygon evaluation for as long as it ran.
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
     * [heldSignedBoundaryDistanceMeters], [heldHorizontalAccuracyMeters] and [heldFixAgeSeconds]
     * describe the fix the arrival rests on, not whichever later fix released it.
     *
     * [heldFixAgeSeconds] is that fix's age at the moment the verdict is emitted, so it counts the
     * time the hold waited. Reporting the age captured when the hold opened made a fix held for
     * 15 s read as `age=0.4`, which is a capture claiming fresher evidence than the verdict used.
     */
    data class Committed(
        val reason: PolygonArrivalCommit?,
        val heldSignedBoundaryDistanceMeters: Double?,
        val heldHorizontalAccuracyMeters: Double,
        val heldFixAgeSeconds: Double
    ) : HeldArrivalOutcome

    /**
     * No arrival to report from the hold, for any of four reasons the records distinguish: none was
     * open, the fix contradicted it, it went stale, or the fence is already committed inside.
     *
     * One case rather than four because the caller does the same thing in all of them — judge the
     * fix on its own merits. Returning early instead swallowed a departure: a fix that positively
     * places the device outside is exactly the fix that should emit EXIT when the fence is
     * committed inside, and the hold being open is no reason to drop it.
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
     * Marks which fences are holding a marginal arrival.
     *
     * Two confirmations, so opening a hold yields no transition and [resolveHeldArrival] owns what
     * the next fix means. Raising it would put a second fix back in charge of whether an arrival is
     * reported at all, which is the rule this deliberately abandoned.
     */
    private val arrivalConfirmations: PolygonTransitionStateMachine =
        PolygonTransitionStateMachine(requiredConfirmations = 2)
) {
    private val latestElapsedRealtimeNanos = mutableMapOf<String, Long>()

    /**
     * What each held arrival is counting: the fix that decided it, and when it was counted from.
     *
     * Carries the measurements so the committed verdict can report the fix the arrival rests on
     * rather than whichever later fix released it. Reporting the releasing fix put a negative
     * `edge` on a `polygon.decided ENTER`, which is a record that contradicts itself.
     *
     * Derived rather than maintained alongside the state machine: [resolveHeldArrival] drops the
     * entry whenever no hold is pending, so a write site that skips a removal costs one pass
     * rather than a wrong verdict.
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
            // A held arrival is settled here, before anything else this fix might say is read.
            // It commits unless the fix positively places the device outside, so the only fix that
            // can lose a visit is one that argues against it — a fix that merely cannot judge adds
            // nothing to the one being held, which is not the same as contradicting it.
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
                        // The held fix, not this one: the arrival rests on the measurement that
                        // decided it, and reporting the releasing fix put a negative edge on an
                        // ENTER.
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
     * Returns the ids that were still holding an arrival, so the caller can report them once it is
     * outside its lock. The record cannot be emitted here: every caller holds one, and the host's
     * log dispatcher is customer code.
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
     * The rule is iOS's, and the asymmetry is the whole point: a marginal arrival is reported
     * unless a fix positively places the device outside. Requiring a second fix to AGREE was never
     * once satisfied in four field captures — `cor=true` appears in none of them — because a
     * stationary device is exactly the case that cannot supply one: the fused provider re-emits the
     * coordinate it already carried, and no number of those is a second observation. A fix that
     * merely cannot judge adds nothing to the one being held, which is not evidence against it.
     *
     * Stated carefully, because the captures do not show this rule losing a real visit. The two
     * holds it ever refused were both false, and the one real arrival the field lost never reached
     * a hold at all: the flat accuracy ceiling refused it first. What the captures show is that the
     * requirement is unsatisfiable here, not that it has already cost a visit.
     *
     * A hold is only ever open while the fence is committed outside, so nothing here rechecks
     * that: no production route commits a polygon inside behind this processor. Both
     * `reconcileEnteredIds` call sites filter polygons out of `inside`, `commitBusinessTransition`
     * is reached only through the single engine that owns this processor, and every ENTER it
     * decides clears the hold in the same pass.
     *
     * Ordered so the incoming fix is judged before the clock is consulted. The staleness bound
     * below would otherwise let the arbitrary 60 s boundary decide what a contradicting fix means:
     * the same fix reading 47 m outside broke the arrival at 59 s and committed it at 61 s.
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
        // iOS's predicate exactly: any fix that could judge this venue and read outside blocks the
        // arrival. Not the departure rule, which also demands clearance beyond the accuracy plus a
        // 20 m margin — that is far looser, and at indoor accuracy it is looser by a lot. A fix
        // reading 8 m outside at 15 m accuracy would commit the arrival under it, and the false
        // visit would then stay open until something cleared the ring by 120 m.
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
        // A pending arrival is evidence about a visit happening now. Past the window it no longer
        // describes where the device is, so it is dropped rather than reported at a time we cannot
        // defend — the one branch here that still loses a visit. Reaching it needs the precise fix
        // the callback asks for to go missing, which it did in none of the 406 requests the field
        // captures recorded.
        if (heldForNanos > MAX_CORROBORATION_GAP_NANOS) {
            clearHold(polygonId)
            records += PolygonRouteRecord.ArrivalExpired(
                geofenceId = polygonId,
                reason = PolygonArrivalExpiry.WINDOW_ELAPSED,
                heldForSeconds = heldForNanos.toDouble() / NANOS_PER_SECOND
            )
            return HeldArrivalOutcome.NotCommitted
        }
        // Position alone, because position is the part the provider carries forward while it
        // substitutes the accuracy: the re-emission arrives with accuracy replaced by a placeholder
        // and the coordinate identical to 6 dp.
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
     * GMS answers a hold's precise-fix request with that same fix when it is a fraction of a second
     * old, even at a max update age of zero. Skipped as not-newer, the hold then waits for a fix
     * minutes away and the visit is lost, so that answer settles it as not independent instead.
     * Only that answer, which the caller marks by passing `answersHeldFixAt` as the stamp of the fix
     * its request was made from. The same fix arriving by another path while the request is still
     * out is refused as before, so it cannot commit the hold ahead of a newer answer that reads
     * outside.
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

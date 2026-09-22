package io.customer.geofence.polygon

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
        val corroborated: Boolean
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

    /**
     * A held arrival handed a fix at the position it is already counting, so it did not advance.
     *
     * [sinceCountedFixSeconds] is what makes the record readable: `edge` and `acc` cannot separate
     * a re-delivery milliseconds later from an identical coordinate a minute later, and only the
     * second is a second opinion this refused.
     */
    data class ArrivalEcho(
        override val geofenceId: String,
        val signedBoundaryDistanceMeters: Double?,
        val horizontalAccuracyMeters: Double,
        val fixAgeSeconds: Double,
        val sinceCountedFixSeconds: Double?
    ) : PolygonRouteRecord
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
     * Gates arrivals: the evidence rule has no clearance margin, so a marginal fix needs a second.
     *
     * The echo guard below compares against the most recently counted sample only, which is
     * sufficient at two confirmations and not at more: at three, an A, B, A sequence would count
     * three confirmations from two positions. Raising this needs the guard to keep the whole run.
     */
    private val arrivalConfirmations: PolygonTransitionStateMachine =
        PolygonTransitionStateMachine(requiredConfirmations = 2)
) {
    private val latestElapsedRealtimeNanos = mutableMapOf<String, Long>()

    /**
     * The measurement each held arrival is already counting, so an echo of it cannot corroborate it.
     *
     * Derived, like [arrivalConfirmationNanos]: [retireStaleArrivalConfirmation] runs before the
     * comparison on every pass and drops the entry whenever no hold is pending, so a write site
     * that skips a removal costs one pass rather than a wrong verdict. Two hold-ending branches do
     * exactly that, the decisive ENTER and the EXIT, and both are safe for that reason.
     */
    private val lastCountedSample = mutableMapOf<String, PolygonLocationSample>()

    fun process(
        fences: List<PolygonFence>,
        sample: PolygonLocationSample,
        elapsedRealtimeNanos: Long,
        fixAgeSeconds: Double,
        committedStates: Map<String, PolygonCommittedState>
    ): PolygonRouteOutcome {
        require(elapsedRealtimeNanos >= 0L) { "elapsed realtime must be non-negative" }
        require(fences.map(PolygonFence::id).distinct().size == fences.size) {
            "polygon ids must be unique"
        }

        val activeIds = fences.mapTo(mutableSetOf(), PolygonFence::id)
        trackedFenceIds.filterNot(activeIds::contains).forEach { retired ->
            arrivalConfirmations.clear(retired)
            arrivalConfirmationNanos.remove(retired)
        }
        latestElapsedRealtimeNanos.keys.retainAll(activeIds)
        arrivalConfirmationNanos.keys.retainAll(activeIds)
        lastCountedSample.keys.retainAll(activeIds)
        trackedFenceIds.clear()
        trackedFenceIds.addAll(activeIds)

        val records = mutableListOf<PolygonRouteRecord>()
        val detections = fences.mapNotNull { fence ->
            val latest = latestElapsedRealtimeNanos[fence.id]
            if (latest != null && elapsedRealtimeNanos <= latest) return@mapNotNull null
            latestElapsedRealtimeNanos[fence.id] = elapsedRealtimeNanos
            retireStaleArrivalConfirmation(fence.id, elapsedRealtimeNanos, records)
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
            val isTransitionEvidence =
                committedState == PolygonCommittedState.OUTSIDE && evidence == PolygonEvidence.ENTER ||
                    committedState == PolygonCommittedState.INSIDE && evidence == PolygonEvidence.EXIT
            if (!isTransitionEvidence) {
                // Before breaking the run: a fix at the coordinate the hold is already counting is
                // not evidence against the arrival, because it is not a new observation of where
                // the device is. It usually arrives over the accuracy ceiling, which is what makes
                // it read as disagreement, and on this device that is what the precise fix a hold
                // asks for mostly returns. Reading it as a broken run destroyed the hold seconds
                // after opening it and lost the visit faster than not asking at all.
                echoOfHeldPosition(fence.id, sample, result, fixAgeSeconds, elapsedRealtimeNanos)
                    ?.let { echo ->
                        records += echo
                        return@mapNotNull null
                    }
                // Breaks a run of agreeing arrival fixes: the requirement below is consecutive, and
                // a fix too coarse to judge is not agreement.
                val hadPendingArrival = arrivalConfirmations.hasPending(fence.id)
                confirmArrival(fence.id, committedState, evidence, elapsedRealtimeNanos, sample)
                // The third way a hold ends, and the commonest. Without its own record the capture
                // shows an arrival.pending with no counterpart and cannot tell a hold that was
                // broken from one still waiting.
                if (hadPendingArrival && !arrivalConfirmations.hasPending(fence.id)) {
                    records += PolygonRouteRecord.ArrivalExpired(
                        geofenceId = fence.id,
                        reason = PolygonArrivalExpiry.EVIDENCE_BROKEN
                    )
                }
                return@mapNotNull null
            }
            val transition = when (evidence) {
                // A fix whose uncertainty does not reach the ring decides on its own: a passer-by
                // on the pavement cannot produce one. A marginal fix can, so it needs a second
                // agreeing fix, which a real visit supplies on the next sample and someone walking
                // past usually does not.
                PolygonEvidence.ENTER -> when {
                    !result.requiresCorroboration -> {
                        arrivalConfirmations.clear(fence.id)
                        PolygonTransition.ENTER
                    }
                    // Corroboration asks whether a second observation also puts the device
                    // inside. An identical coordinate answers that with the first observation, so
                    // it cannot corroborate however the fix is labelled. The dedupe above only
                    // refuses a repeated elapsed-realtime stamp, and the fused provider re-emits a
                    // carried-forward position under a fresh stamp.
                    //
                    // Position alone, deliberately, because position is the part measured to carry
                    // forward and accuracy is the part measured to be substituted: on this device
                    // the re-emission arrives with accuracy replaced by a placeholder while the
                    // coordinate is identical to 6 dp. Requiring both to match would let the same
                    // coordinate at a different sub-ceiling accuracy confirm an arrival from one
                    // observation, which is the defect this exists to close.
                    //
                    // The cost is real and accepted: a parked device whose coordinate is snapped
                    // identical can never corroborate itself, so its arrival waits for the precise
                    // fix the callback path now asks for, and is dropped if that cannot produce a
                    // different position either. Claiming a second opinion we do not have is worse.
                    //
                    // The early return skips confirmArrival deliberately: an echo neither advances
                    // the run nor refreshes the hold's stamp, so it cannot buy time for a genuine
                    // fix arriving after the window.
                    else -> {
                        val echo = echoOfHeldPosition(
                            fence.id,
                            sample,
                            result,
                            fixAgeSeconds,
                            elapsedRealtimeNanos
                        )
                        if (echo != null) {
                            records += echo
                            return@mapNotNull null
                        }
                        confirmArrival(fence.id, committedState, evidence, elapsedRealtimeNanos, sample)
                            ?: run {
                                // Decided ENTER, held for a second fix. Without this record the
                                // branch consumes a fix and emits nothing, which is
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
                }
                // Departure keeps its clearance margin, so it needs no second opinion, and delaying
                // it would report a visit as still running after it ended.
                PolygonEvidence.EXIT -> {
                    arrivalConfirmations.clear(fence.id)
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
                corroborated = result.requiresCorroboration
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
        arrivalConfirmationNanos.clear()
        lastCountedSample.clear()
        arrivalConfirmations.clearAll()
        return discarded
    }

    /** True when the fence was still holding an arrival that this discarded. */
    fun clear(polygonId: String): Boolean {
        val wasPending = arrivalConfirmations.hasPending(polygonId)
        trackedFenceIds.remove(polygonId)
        latestElapsedRealtimeNanos.remove(polygonId)
        arrivalConfirmationNanos.remove(polygonId)
        lastCountedSample.remove(polygonId)
        arrivalConfirmations.clear(polygonId)
        return wasPending
    }

    /**
     * A pending arrival is evidence about the visit happening now. The confirmation counts agreeing
     * fixes and knows nothing about when they arrived, so without this a marginal fix from one pass
     * and a marginal fix from a pass minutes later combine into an arrival neither observed.
     */
    private fun retireStaleArrivalConfirmation(
        polygonId: String,
        elapsedRealtimeNanos: Long,
        records: MutableList<PolygonRouteRecord>
    ) {
        // Derived from the state machine rather than maintained alongside it. Three call sites end
        // a hold and only one of them owns this map, so every attempt to keep the two in step at
        // the write sites has left a stamp behind and reported an expiry for an arrival that never
        // existed or had already been honoured.
        if (!arrivalConfirmations.hasPending(polygonId)) {
            arrivalConfirmationNanos.remove(polygonId)
            lastCountedSample.remove(polygonId)
            return
        }
        val observedAt = arrivalConfirmationNanos[polygonId] ?: return
        val heldForNanos = elapsedRealtimeNanos - observedAt
        if (heldForNanos > MAX_CORROBORATION_GAP_NANOS) {
            // The counterpart to the pending record. Without it a capture cannot separate a hold
            // that completed from one that died, which is the whole question the field has to
            // answer about corroboration.
            records += PolygonRouteRecord.ArrivalExpired(
                geofenceId = polygonId,
                reason = PolygonArrivalExpiry.WINDOW_ELAPSED,
                heldForSeconds = heldForNanos.toDouble() / NANOS_PER_SECOND
            )
            arrivalConfirmations.clear(polygonId)
            arrivalConfirmationNanos.remove(polygonId)
            lastCountedSample.remove(polygonId)
        }
    }

    /**
     * The record to emit when [sample] repeats the position [polygonId]'s hold is already counting,
     * or null when it is a different position and the caller should carry on.
     *
     * Both callers then return without touching the run: such a fix can neither corroborate the
     * arrival nor contradict it, because it is not a new observation of where the device is. Only
     * the coordinate is compared, since that is the part the provider carries forward while it
     * substitutes the accuracy.
     *
     * Null whenever no hold is live: [lastCountedSample] is derived, and
     * [retireStaleArrivalConfirmation] has already run for this fence on this pass.
     */
    private fun echoOfHeldPosition(
        polygonId: String,
        sample: PolygonLocationSample,
        result: PolygonEvidenceResult,
        fixAgeSeconds: Double,
        elapsedRealtimeNanos: Long
    ): PolygonRouteRecord.ArrivalEcho? {
        if (lastCountedSample[polygonId]?.coordinate != sample.coordinate) return null
        return PolygonRouteRecord.ArrivalEcho(
            geofenceId = polygonId,
            signedBoundaryDistanceMeters = result.signedBoundaryDistanceMeters,
            horizontalAccuracyMeters = sample.horizontalAccuracyMeters,
            fixAgeSeconds = fixAgeSeconds,
            sinceCountedFixSeconds = arrivalConfirmationNanos[polygonId]
                ?.let { (elapsedRealtimeNanos - it).toDouble() / NANOS_PER_SECOND }
        )
    }

    private fun confirmArrival(
        polygonId: String,
        committedState: PolygonCommittedState,
        evidence: PolygonEvidence,
        elapsedRealtimeNanos: Long,
        sample: PolygonLocationSample
    ): PolygonTransition? {
        val transition = arrivalConfirmations.evaluate(polygonId, committedState, evidence)
        // Only a fence actually holding a part-confirmed arrival is timed. This is also reached to
        // break a run of agreeing fixes, and stamping those made every quiet fence look like a
        // pending arrival that later expired. The read in retireStaleArrivalConfirmation re-derives
        // this, so a stamp left behind by another call site cannot outlive its hold.
        if (arrivalConfirmations.hasPending(polygonId)) {
            arrivalConfirmationNanos[polygonId] = elapsedRealtimeNanos
            lastCountedSample[polygonId] = sample
        } else {
            arrivalConfirmationNanos.remove(polygonId)
            lastCountedSample.remove(polygonId)
        }
        return transition
    }

    private val trackedFenceIds = mutableSetOf<String>()
    private val arrivalConfirmationNanos = mutableMapOf<String, Long>()

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

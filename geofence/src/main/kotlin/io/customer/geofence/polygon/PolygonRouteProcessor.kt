package io.customer.geofence.polygon

import io.customer.geofence.GeofenceLogger

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

/** Evaluates one ordered location stream against the currently active polygons. */
internal class PolygonRouteProcessor(
    private val accuracyEvaluator: PolygonAccuracyEvaluator = PolygonAccuracyEvaluator(),
    /** Gates arrivals: the evidence rule has no clearance margin, so a marginal fix needs a second. */
    private val arrivalConfirmations: PolygonTransitionStateMachine =
        PolygonTransitionStateMachine(requiredConfirmations = 2),
    private val logger: GeofenceLogger
) {
    private val latestElapsedRealtimeNanos = mutableMapOf<String, Long>()

    fun process(
        fences: List<PolygonFence>,
        sample: PolygonLocationSample,
        elapsedRealtimeNanos: Long,
        fixAgeSeconds: Double,
        committedStates: Map<String, PolygonCommittedState>
    ): List<PolygonTransitionDetection> {
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
        trackedFenceIds.clear()
        trackedFenceIds.addAll(activeIds)

        return fences.mapNotNull { fence ->
            val latest = latestElapsedRealtimeNanos[fence.id]
            if (latest != null && elapsedRealtimeNanos <= latest) return@mapNotNull null
            latestElapsedRealtimeNanos[fence.id] = elapsedRealtimeNanos
            retireStaleArrivalConfirmation(fence.id, elapsedRealtimeNanos)
            val committedState = committedStates[fence.id] ?: PolygonCommittedState.OUTSIDE
            val result = accuracyEvaluator.decisiveEvidenceFor(fence.geometry, sample, committedState)
            val evidence = result.evidence
            result.undecidedReason?.let { reason ->
                logger.logPolygonUndecided(
                    geofenceId = fence.id,
                    reason = reason,
                    signedBoundaryDistanceMeters = result.signedBoundaryDistanceMeters,
                    horizontalAccuracyMeters = sample.horizontalAccuracyMeters,
                    fixAgeSeconds = fixAgeSeconds
                )
            }
            if (result.agreedWithCommittedState) {
                logger.logPolygonUnchanged(
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
                // Breaks a run of agreeing arrival fixes: the requirement below is consecutive, and
                // a fix too coarse to judge is not agreement.
                confirmArrival(fence.id, committedState, evidence, elapsedRealtimeNanos)
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
                    else -> confirmArrival(fence.id, committedState, evidence, elapsedRealtimeNanos)
                        ?: run {
                            // Decided ENTER, held for a second fix. Without this record the branch
                            // consumes a fix and emits nothing, which is indistinguishable in a
                            // capture from a callback that never arrived.
                            logger.logPolygonArrivalPending(
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
                    arrivalConfirmations.clear(fence.id)
                    PolygonTransition.EXIT
                }
                PolygonEvidence.AMBIGUOUS -> return@mapNotNull null
            }
            recorded(
                fence = fence,
                transition = transition,
                result = result,
                sample = sample,
                fixAgeSeconds = fixAgeSeconds,
                corroborated = result.requiresCorroboration
            )
        }
    }

    fun clear() {
        latestElapsedRealtimeNanos.clear()
        trackedFenceIds.clear()
        arrivalConfirmationNanos.clear()
        arrivalConfirmations.clearAll()
    }

    fun clear(polygonId: String) {
        trackedFenceIds.remove(polygonId)
        latestElapsedRealtimeNanos.remove(polygonId)
        arrivalConfirmationNanos.remove(polygonId)
        arrivalConfirmations.clear(polygonId)
    }

    /**
     * A pending arrival is evidence about the visit happening now. The confirmation counts agreeing
     * fixes and knows nothing about when they arrived, so without this a marginal fix from one pass
     * and a marginal fix from a pass minutes later combine into an arrival neither observed.
     */
    private fun retireStaleArrivalConfirmation(polygonId: String, elapsedRealtimeNanos: Long) {
        // Derived from the state machine rather than maintained alongside it. Three call sites end
        // a hold and only one of them owns this map, so every attempt to keep the two in step at
        // the write sites has left a stamp behind and reported an expiry for an arrival that never
        // existed or had already been honoured.
        if (!arrivalConfirmations.hasPending(polygonId)) {
            arrivalConfirmationNanos.remove(polygonId)
            return
        }
        val observedAt = arrivalConfirmationNanos[polygonId] ?: return
        val heldForNanos = elapsedRealtimeNanos - observedAt
        if (heldForNanos > MAX_CORROBORATION_GAP_NANOS) {
            // The counterpart to the pending record. Without it a capture cannot separate a hold
            // that completed from one that died, which is the whole question the field has to
            // answer about corroboration.
            logger.logPolygonArrivalExpired(
                geofenceId = polygonId,
                heldForSeconds = heldForNanos.toDouble() / NANOS_PER_SECOND
            )
            arrivalConfirmations.clear(polygonId)
            arrivalConfirmationNanos.remove(polygonId)
        }
    }

    private fun confirmArrival(
        polygonId: String,
        committedState: PolygonCommittedState,
        evidence: PolygonEvidence,
        elapsedRealtimeNanos: Long
    ): PolygonTransition? {
        val transition = arrivalConfirmations.evaluate(polygonId, committedState, evidence)
        // Only a fence actually holding a part-confirmed arrival is timed. This is also reached to
        // break a run of agreeing fixes, and stamping those made every quiet fence look like a
        // pending arrival that later expired. The read in retireStaleArrivalConfirmation re-derives
        // this, so a stamp left behind by another call site cannot outlive its hold.
        if (arrivalConfirmations.hasPending(polygonId)) {
            arrivalConfirmationNanos[polygonId] = elapsedRealtimeNanos
        } else {
            arrivalConfirmationNanos.remove(polygonId)
        }
        return transition
    }

    private fun recorded(
        fence: PolygonFence,
        transition: PolygonTransition,
        result: PolygonEvidenceResult,
        sample: PolygonLocationSample,
        fixAgeSeconds: Double,
        corroborated: Boolean
    ): PolygonTransitionDetection {
        logger.logPolygonDecided(
            geofenceId = fence.id,
            transitionName = transition.name,
            signedBoundaryDistanceMeters = result.signedBoundaryDistanceMeters,
            horizontalAccuracyMeters = sample.horizontalAccuracyMeters,
            fixAgeSeconds = fixAgeSeconds,
            corroborated = corroborated
        )
        return PolygonTransitionDetection(fence.id, transition, fence.regionRevision)
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

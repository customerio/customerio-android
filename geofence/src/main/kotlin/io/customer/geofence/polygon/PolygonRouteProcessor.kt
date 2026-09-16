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

internal enum class PolygonEvidencePolicy {
    CONFIRMED,
    DECISIVE_SINGLE_FIX
}

/** Evaluates one ordered location stream against the currently active polygons. */
internal class PolygonRouteProcessor(
    private val accuracyEvaluator: PolygonAccuracyEvaluator = PolygonAccuracyEvaluator(),
    private val stateMachine: PolygonTransitionStateMachine = PolygonTransitionStateMachine(),
    /**
     * Separate from [stateMachine] and deliberately shorter: this gates arrivals on the decisive
     * path, where the evidence rule has no clearance margin at all.
     */
    private val arrivalConfirmations: PolygonTransitionStateMachine =
        PolygonTransitionStateMachine(requiredConfirmations = 2),
    private val minimumEvidenceIntervalNanos: Long = 0L,
    private val logger: GeofenceLogger
) {
    private val latestElapsedRealtimeNanos = mutableMapOf<String, Long>()

    fun process(
        fences: List<PolygonFence>,
        sample: PolygonLocationSample,
        elapsedRealtimeNanos: Long,
        fixAgeSeconds: Double,
        committedStates: Map<String, PolygonCommittedState>,
        evidencePolicy: PolygonEvidencePolicy = PolygonEvidencePolicy.CONFIRMED
    ): List<PolygonTransitionDetection> {
        require(elapsedRealtimeNanos >= 0L) { "elapsed realtime must be non-negative" }
        require(fences.map(PolygonFence::id).distinct().size == fences.size) {
            "polygon ids must be unique"
        }

        val activeIds = fences.mapTo(mutableSetOf(), PolygonFence::id)
        trackedFenceIds.filterNot(activeIds::contains).forEach { retired ->
            stateMachine.clear(retired)
            arrivalConfirmations.clear(retired)
            arrivalConfirmationNanos.remove(retired)
        }
        latestElapsedRealtimeNanos.keys.retainAll(activeIds)
        lastEvidenceElapsedNanos.keys.retainAll(activeIds)
        arrivalConfirmationNanos.keys.retainAll(activeIds)
        trackedFenceIds.clear()
        trackedFenceIds.addAll(activeIds)

        return fences.mapNotNull { fence ->
            val latest = latestElapsedRealtimeNanos[fence.id]
            if (latest != null && elapsedRealtimeNanos <= latest) return@mapNotNull null
            latestElapsedRealtimeNanos[fence.id] = elapsedRealtimeNanos
            retireStaleArrivalConfirmation(fence.id, elapsedRealtimeNanos)
            val committedState = committedStates[fence.id] ?: PolygonCommittedState.OUTSIDE
            val result = when (evidencePolicy) {
                PolygonEvidencePolicy.CONFIRMED ->
                    accuracyEvaluator.evidenceFor(fence.geometry, sample, committedState)
                PolygonEvidencePolicy.DECISIVE_SINGLE_FIX ->
                    accuracyEvaluator.decisiveEvidenceFor(fence.geometry, sample, committedState)
            }
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
                lastEvidenceElapsedNanos.remove(fence.id)
                stateMachine.evaluate(fence.id, committedState, evidence)
                // Breaks a run of agreeing arrival fixes: the requirement below is consecutive, and
                // a fix too coarse to judge is not agreement.
                confirmArrival(fence.id, committedState, evidence, elapsedRealtimeNanos)
                return@mapNotNull null
            }
            if (evidencePolicy == PolygonEvidencePolicy.DECISIVE_SINGLE_FIX) {
                lastEvidenceElapsedNanos.remove(fence.id)
                stateMachine.clear(fence.id)
                val transition = when (evidence) {
                    // A fix whose uncertainty does not reach the ring decides on its own: a
                    // passer-by on the pavement cannot produce one. A marginal fix can, so it
                    // needs a second agreeing fix — which a real visit supplies on the next
                    // sample and someone walking past usually does not.
                    PolygonEvidence.ENTER -> when {
                        !result.requiresCorroboration -> {
                            arrivalConfirmations.clear(fence.id)
                            PolygonTransition.ENTER
                        }
                        else -> confirmArrival(fence.id, committedState, evidence, elapsedRealtimeNanos)
                            ?: return@mapNotNull null
                    }
                    // Departure keeps its clearance margin, so it needs no second opinion, and
                    // delaying it would report a visit as still running after it ended.
                    PolygonEvidence.EXIT -> {
                        arrivalConfirmations.clear(fence.id)
                        PolygonTransition.EXIT
                    }
                    PolygonEvidence.AMBIGUOUS -> return@mapNotNull null
                }
                return@mapNotNull recorded(
                    fence = fence,
                    transition = transition,
                    result = result,
                    sample = sample,
                    fixAgeSeconds = fixAgeSeconds,
                    corroborated = result.requiresCorroboration
                )
            }
            val previousEvidenceTime = lastEvidenceElapsedNanos[fence.id]
            if (
                previousEvidenceTime != null &&
                elapsedRealtimeNanos - previousEvidenceTime < minimumEvidenceIntervalNanos
            ) {
                return@mapNotNull null
            }
            lastEvidenceElapsedNanos[fence.id] = elapsedRealtimeNanos
            stateMachine.evaluate(fence.id, committedState, evidence)?.let { transition ->
                recorded(
                    fence = fence,
                    transition = transition,
                    result = result,
                    sample = sample,
                    fixAgeSeconds = fixAgeSeconds,
                    // This policy reaches a transition by repeated agreeing fixes, so every
                    // transition it produces is corroborated by construction.
                    corroborated = true
                )
            }
        }
    }

    fun clear() {
        latestElapsedRealtimeNanos.clear()
        trackedFenceIds.clear()
        lastEvidenceElapsedNanos.clear()
        arrivalConfirmationNanos.clear()
        stateMachine.clearAll()
        arrivalConfirmations.clearAll()
    }

    fun clear(polygonId: String) {
        trackedFenceIds.remove(polygonId)
        latestElapsedRealtimeNanos.remove(polygonId)
        lastEvidenceElapsedNanos.remove(polygonId)
        arrivalConfirmationNanos.remove(polygonId)
        stateMachine.clear(polygonId)
        arrivalConfirmations.clear(polygonId)
    }

    /**
     * A pending arrival is evidence about the visit happening now. The confirmation counts agreeing
     * fixes and knows nothing about when they arrived, so without this a marginal fix from one pass
     * and a marginal fix from a pass minutes later combine into an arrival neither observed.
     */
    private fun retireStaleArrivalConfirmation(polygonId: String, elapsedRealtimeNanos: Long) {
        val observedAt = arrivalConfirmationNanos[polygonId] ?: return
        if (elapsedRealtimeNanos - observedAt > MAX_CORROBORATION_GAP_NANOS) {
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
        arrivalConfirmationNanos[polygonId] = elapsedRealtimeNanos
        return arrivalConfirmations.evaluate(polygonId, committedState, evidence)
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
    private val lastEvidenceElapsedNanos = mutableMapOf<String, Long>()
    private val arrivalConfirmationNanos = mutableMapOf<String, Long>()

    private companion object {
        /**
         * How far apart two marginal fixes may be and still describe one visit. The approach session
         * samples every 15 s, so a corroborating fix is normally the next one; this allows a few
         * missed deliveries without letting a separate pass at the same shop complete an arrival.
         * Bounds the age of the evidence, not how much of it is required.
         */
        const val MAX_CORROBORATION_GAP_NANOS = 60_000_000_000L
    }
}

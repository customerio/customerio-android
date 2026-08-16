package io.customer.geofence.polygon

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
    private val stateMachine: PolygonTransitionStateMachine = PolygonTransitionStateMachine(),
    private val minimumEvidenceIntervalNanos: Long = 0L
) {
    private var latestElapsedRealtimeNanos: Long? = null

    fun process(
        fences: List<PolygonFence>,
        sample: PolygonLocationSample,
        elapsedRealtimeNanos: Long,
        committedStates: Map<String, PolygonCommittedState>
    ): List<PolygonTransitionDetection> {
        require(elapsedRealtimeNanos >= 0L) { "elapsed realtime must be non-negative" }
        require(fences.map(PolygonFence::id).distinct().size == fences.size) {
            "polygon ids must be unique"
        }

        val latest = latestElapsedRealtimeNanos
        if (latest != null && elapsedRealtimeNanos <= latest) return emptyList()
        latestElapsedRealtimeNanos = elapsedRealtimeNanos

        val activeIds = fences.mapTo(mutableSetOf(), PolygonFence::id)
        trackedFenceIds.filterNot(activeIds::contains).forEach(stateMachine::clear)
        lastEvidenceElapsedNanos.keys.retainAll(activeIds)
        trackedFenceIds.clear()
        trackedFenceIds.addAll(activeIds)

        return fences.mapNotNull { fence ->
            val committedState = committedStates[fence.id] ?: PolygonCommittedState.OUTSIDE
            val evidence = accuracyEvaluator.evidenceFor(fence.geometry, sample, committedState)
            val isTransitionEvidence =
                committedState == PolygonCommittedState.OUTSIDE && evidence == PolygonEvidence.ENTER ||
                    committedState == PolygonCommittedState.INSIDE && evidence == PolygonEvidence.EXIT
            if (!isTransitionEvidence) {
                lastEvidenceElapsedNanos.remove(fence.id)
                stateMachine.evaluate(fence.id, committedState, evidence)
                return@mapNotNull null
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
                PolygonTransitionDetection(fence.id, transition, fence.regionRevision)
            }
        }
    }

    fun clear() {
        latestElapsedRealtimeNanos = null
        trackedFenceIds.clear()
        lastEvidenceElapsedNanos.clear()
        stateMachine.clearAll()
    }

    fun clear(polygonId: String) {
        trackedFenceIds.remove(polygonId)
        lastEvidenceElapsedNanos.remove(polygonId)
        stateMachine.clear(polygonId)
    }

    private val trackedFenceIds = mutableSetOf<String>()
    private val lastEvidenceElapsedNanos = mutableMapOf<String, Long>()
}

package io.customer.geofence.polygon

internal enum class PolygonTransition {
    ENTER,
    EXIT
}

/** Holds only session-local confirmation evidence. The caller owns durable committed state. */
internal class PolygonTransitionStateMachine(
    private val requiredConfirmations: Int = 3
) {
    private data class PendingTransition(
        val transition: PolygonTransition,
        val confirmations: Int
    )

    private val pendingByPolygon = mutableMapOf<String, PendingTransition>()

    init {
        require(requiredConfirmations in 2..5) { "required confirmations are outside safe bounds" }
    }

    fun evaluate(
        polygonId: String,
        committedState: PolygonCommittedState,
        evidence: PolygonEvidence
    ): PolygonTransition? {
        require(polygonId.isNotBlank()) { "polygon id cannot be blank" }
        val candidate = when {
            committedState == PolygonCommittedState.OUTSIDE && evidence == PolygonEvidence.ENTER ->
                PolygonTransition.ENTER
            committedState == PolygonCommittedState.INSIDE && evidence == PolygonEvidence.EXIT ->
                PolygonTransition.EXIT
            else -> null
        }
        if (candidate == null) {
            pendingByPolygon.remove(polygonId)
            return null
        }

        val current = pendingByPolygon[polygonId]
        val confirmations = if (current?.transition == candidate) current.confirmations + 1 else 1
        if (confirmations < requiredConfirmations) {
            pendingByPolygon[polygonId] = PendingTransition(candidate, confirmations)
            return null
        }

        pendingByPolygon.remove(polygonId)
        return candidate
    }

    fun clear(polygonId: String) {
        pendingByPolygon.remove(polygonId)
    }

    fun clearAll() {
        pendingByPolygon.clear()
    }
}

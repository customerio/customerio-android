package io.customer.geofence.polygon

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test

class PolygonTransitionStateMachineTest {
    private val stateMachine = PolygonTransitionStateMachine(requiredConfirmations = 3)

    @Test
    fun evaluate_whenThreeConsecutiveEnterSamplesArrive_thenCommitsEnter() {
        evaluate("campus", PolygonEvidence.ENTER).shouldBeNull()
        evaluate("campus", PolygonEvidence.ENTER).shouldBeNull()

        evaluate("campus", PolygonEvidence.ENTER) shouldBeEqualTo PolygonTransition.ENTER
    }

    @Test
    fun evaluate_whenEvidenceBecomesAmbiguous_thenResetsPendingTransition() {
        evaluate("campus", PolygonEvidence.ENTER).shouldBeNull()
        evaluate("campus", PolygonEvidence.ENTER).shouldBeNull()
        evaluate("campus", PolygonEvidence.AMBIGUOUS).shouldBeNull()
        evaluate("campus", PolygonEvidence.ENTER).shouldBeNull()
        evaluate("campus", PolygonEvidence.ENTER).shouldBeNull()

        evaluate("campus", PolygonEvidence.ENTER) shouldBeEqualTo PolygonTransition.ENTER
    }

    @Test
    fun evaluate_whenEvidenceTargetsCurrentState_thenDoesNotCommit() {
        stateMachine.evaluate(
            polygonId = "campus",
            committedState = PolygonCommittedState.OUTSIDE,
            evidence = PolygonEvidence.EXIT
        ).shouldBeNull()
    }

    @Test
    fun evaluate_whenPolygonsInterleave_thenKeepsConfirmationCountsIndependent() {
        evaluate("campus-a", PolygonEvidence.ENTER).shouldBeNull()
        evaluate("campus-b", PolygonEvidence.ENTER).shouldBeNull()
        evaluate("campus-a", PolygonEvidence.ENTER).shouldBeNull()
        evaluate("campus-b", PolygonEvidence.ENTER).shouldBeNull()

        evaluate("campus-a", PolygonEvidence.ENTER) shouldBeEqualTo PolygonTransition.ENTER
        evaluate("campus-b", PolygonEvidence.ENTER) shouldBeEqualTo PolygonTransition.ENTER
    }

    private fun evaluate(polygonId: String, evidence: PolygonEvidence): PolygonTransition? =
        stateMachine.evaluate(
            polygonId = polygonId,
            committedState = PolygonCommittedState.OUTSIDE,
            evidence = evidence
        )
}

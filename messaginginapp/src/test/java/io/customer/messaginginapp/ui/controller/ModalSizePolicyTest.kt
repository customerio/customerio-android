package io.customer.messaginginapp.ui.controller

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class ModalSizePolicyTest {
    private fun armedPolicy(): ModalSizePolicy = ModalSizePolicy().apply { arm() }

    private fun ModalSizePolicy.report(vararg heights: Double): List<ModalSizeVerdict> =
        heights.map { onHeightReported(it) }

    @Test
    fun heightReported_givenNotArmed_expectHeightAlwaysApplied() {
        val policy = ModalSizePolicy()

        // Zero heights while the message is still loading must not be judged.
        val verdicts = policy.report(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        verdicts.forEach { it.shouldBeInstanceOf<ModalSizeVerdict.Apply>() }
    }

    @Test
    fun heightReported_givenCollapsedAtZero_expectDegenerateOnceSampleCountReached() {
        val policy = armedPolicy()

        val verdicts = policy.report(0.0, 0.0, 0.0, 0.0)

        verdicts.take(ModalSizePolicy.SAMPLE_COUNT - 1)
            .forEach { it.shouldBeInstanceOf<ModalSizeVerdict.Apply>() }
        verdicts.last() shouldBeEqualTo ModalSizeVerdict.Degenerate
    }

    @Test
    fun heightReported_givenCollapsedAtCollapsedMargin_expectDegenerate() {
        val policy = armedPolicy()

        // iOS reported 8.0 in the field: a body margin left over from an otherwise empty document.
        val verdicts = policy.report(8.0, 8.0, 8.0, 8.0)

        verdicts.last() shouldBeEqualTo ModalSizeVerdict.Degenerate
    }

    @Test
    fun heightReported_givenStaysCollapsed_expectDegenerateReportedOnlyOnce() {
        val policy = armedPolicy()

        val verdicts = policy.report(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        verdicts.count { it == ModalSizeVerdict.Degenerate } shouldBeEqualTo 1
    }

    @Test
    fun heightReported_givenCollapsedThenResolves_expectNoDegenerate() {
        val policy = armedPolicy()

        // A couple of empty measurements before the content settles is normal.
        val verdicts = policy.report(0.0, 0.0, 380.0, 380.0)

        verdicts.none { it == ModalSizeVerdict.Degenerate } shouldBeEqualTo true
        verdicts.last() shouldBeEqualTo ModalSizeVerdict.Apply(380.0)
    }

    @Test
    fun heightReported_givenConstantGrowth_expectViewportDependentWithDelta() {
        val policy = armedPolicy()

        // Observed on device: the height climbs by the body padding on every report.
        val verdicts = policy.report(704.0, 736.0, 768.0, 800.0)

        val verdict = verdicts.last()
        verdict.shouldBeInstanceOf<ModalSizeVerdict.ViewportDependent>()
        (verdict as ModalSizeVerdict.ViewportDependent).deltaInDp shouldBeEqualTo 32.0
        verdict.heightInDp shouldBeEqualTo 800.0
    }

    @Test
    fun heightReported_givenConstantGrowthContinues_expectViewportDependentReportedOnlyOnce() {
        val policy = armedPolicy()

        val verdicts = policy.report(704.0, 736.0, 768.0, 800.0, 832.0, 864.0, 896.0)

        verdicts.count { it is ModalSizeVerdict.ViewportDependent } shouldBeEqualTo 1
    }

    @Test
    fun heightReported_givenTallConstantHeight_expectAppliedAndNotFlagged() {
        val policy = armedPolicy()

        // A message legitimately taller than the screen reports a constant height; the container
        // clamps it. That must not be mistaken for a runaway.
        val verdicts = policy.report(1200.0, 1200.0, 1200.0, 1200.0, 1200.0)

        verdicts.forEach { it shouldBeEqualTo ModalSizeVerdict.Apply(1200.0) }
    }

    @Test
    fun heightReported_givenIrregularGrowthWhileLoading_expectAppliedAndNotFlagged() {
        val policy = armedPolicy()

        // Images and fonts settling produce uneven growth, which is not a runaway.
        val verdicts = policy.report(200.0, 300.0, 380.0, 382.0, 382.0)

        verdicts.none { it is ModalSizeVerdict.ViewportDependent } shouldBeEqualTo true
        verdicts.last() shouldBeEqualTo ModalSizeVerdict.Apply(382.0)
    }

    @Test
    fun heightReported_givenNormalStableHeight_expectAppliedVerbatim() {
        val policy = armedPolicy()

        val verdicts = policy.report(382.0, 382.0, 382.0, 382.0)

        verdicts.forEach { it shouldBeEqualTo ModalSizeVerdict.Apply(382.0) }
    }

    @Test
    fun heightReported_givenShrinkingHeight_expectAppliedAndNotFlagged() {
        val policy = armedPolicy()

        // Going back to a shorter step is a normal multi step transition.
        val verdicts = policy.report(500.0, 468.0, 436.0, 404.0)

        verdicts.forEach { it.shouldBeInstanceOf<ModalSizeVerdict.Apply>() }
    }

    @Test
    fun arm_givenRearmed_expectEarlierSamplesDiscarded() {
        val policy = armedPolicy()
        policy.report(0.0, 0.0, 0.0)

        policy.arm()
        val verdict = policy.onHeightReported(0.0)

        // Only one sample since re-arming, so no verdict can be reached yet.
        verdict shouldBeEqualTo ModalSizeVerdict.Apply(0.0)
    }
}

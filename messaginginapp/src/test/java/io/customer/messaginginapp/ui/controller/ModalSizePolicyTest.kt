package io.customer.messaginginapp.ui.controller

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class ModalSizePolicyTest {
    /** Renderer polls roughly once per second. */
    private val pollIntervalMs = 1000L

    private class FakeClock(var nowMs: Long = 0L)

    private fun policy(
        clock: FakeClock,
        sampleCount: Int = ModalSizePolicy.SAMPLE_COUNT
    ): ModalSizePolicy = ModalSizePolicy(
        sampleCount = sampleCount,
        currentTimeMillis = { clock.nowMs }
    )

    private fun armedPolicy(clock: FakeClock): ModalSizePolicy =
        policy(clock).apply { arm() }

    /** Reports each height, advancing the clock by [stepMs] between reports. */
    private fun ModalSizePolicy.report(
        clock: FakeClock,
        vararg heights: Double,
        stepMs: Long = 1000L
    ): List<ModalSizeVerdict> = heights.mapIndexed { index, height ->
        if (index > 0) clock.nowMs += stepMs
        onHeightReported(height)
    }

    @Test
    fun heightReported_givenNotArmed_expectHeightAlwaysApplied() {
        val clock = FakeClock()
        val policy = policy(clock)

        val verdicts = policy.report(clock, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        verdicts.forEach { it shouldBeEqualTo ModalSizeVerdict.Apply }
    }

    @Test
    fun heightReported_givenCollapsedAtZeroOverTime_expectDegenerate() {
        val clock = FakeClock()
        val policy = armedPolicy(clock)

        val verdicts = policy.report(clock, 0.0, 0.0, 0.0, 0.0)

        verdicts.take(ModalSizePolicy.SAMPLE_COUNT - 1)
            .forEach { it shouldBeEqualTo ModalSizeVerdict.Apply }
        verdicts.last() shouldBeEqualTo ModalSizeVerdict.Degenerate
    }

    @Test
    fun heightReported_givenCollapsedAtCollapsedMargin_expectDegenerate() {
        val clock = FakeClock()
        val policy = armedPolicy(clock)

        // iOS reported 8.0 in the field: a body margin left over from an otherwise empty document.
        val verdicts = policy.report(clock, 8.0, 8.0, 8.0, 8.0)

        verdicts.last() shouldBeEqualTo ModalSizeVerdict.Degenerate
    }

    @Test
    fun heightReported_givenCollapsedBurstWithinMilliseconds_expectNoDegenerate() {
        val clock = FakeClock()
        val policy = armedPolicy(clock)

        // sizeChanged arrives in bursts (it also fires on every window resize), so a healthy
        // message still being laid out can emit several collapsed reports back to back.
        val verdicts = policy.report(clock, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, stepMs = 5L)

        verdicts.forEach { it shouldBeEqualTo ModalSizeVerdict.Apply }
    }

    @Test
    fun heightReported_givenCollapsedBurstThenResolves_expectNoDegenerate() {
        val clock = FakeClock()
        val policy = armedPolicy(clock)

        val verdicts = policy.report(clock, 0.0, 0.0, 0.0, 380.0, stepMs = 10L)

        verdicts.none { it == ModalSizeVerdict.Degenerate } shouldBeEqualTo true
    }

    @Test
    fun heightReported_givenDegenerateNotHandled_expectVerdictRepeated() {
        val clock = FakeClock()
        val policy = armedPolicy(clock)

        // The caller may be unable to act yet; the guard must not disable itself.
        val verdicts = policy.report(clock, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        verdicts.count { it == ModalSizeVerdict.Degenerate } shouldBeEqualTo 3
    }

    @Test
    fun heightReported_givenDegenerateHandled_expectNotReportedAgain() {
        val clock = FakeClock()
        val policy = armedPolicy(clock)
        policy.report(clock, 0.0, 0.0, 0.0, 0.0)

        policy.onDegenerateHandled()
        val verdicts = policy.report(clock, 0.0, 0.0, 0.0)

        verdicts.none { it == ModalSizeVerdict.Degenerate } shouldBeEqualTo true
    }

    @Test
    fun heightReported_givenCollapsedThenResolves_expectNoDegenerate() {
        val clock = FakeClock()
        val policy = armedPolicy(clock)

        val verdicts = policy.report(clock, 0.0, 0.0, 380.0, 380.0)

        verdicts.none { it == ModalSizeVerdict.Degenerate } shouldBeEqualTo true
        verdicts.last() shouldBeEqualTo ModalSizeVerdict.Apply
    }

    @Test
    fun heightReported_givenConstantGrowth_expectViewportDependentWithDelta() {
        val clock = FakeClock()
        val policy = armedPolicy(clock)

        // Observed on device: the height climbs by the body padding on every report.
        val verdicts = policy.report(clock, 704.0, 736.0, 768.0, 800.0)

        val verdict = verdicts.last()
        verdict.shouldBeInstanceOf<ModalSizeVerdict.ViewportDependent>()
        (verdict as ModalSizeVerdict.ViewportDependent).deltaInDp shouldBeEqualTo 32.0
    }

    @Test
    fun heightReported_givenViewportDependentHandled_expectNotReportedAgain() {
        val clock = FakeClock()
        val policy = armedPolicy(clock)
        policy.report(clock, 704.0, 736.0, 768.0, 800.0)

        policy.onViewportDependentHandled()
        val verdicts = policy.report(clock, 832.0, 864.0, 896.0)

        verdicts.none { it is ModalSizeVerdict.ViewportDependent } shouldBeEqualTo true
    }

    @Test
    fun heightReported_givenTallConstantHeight_expectAppliedAndNotFlagged() {
        val clock = FakeClock()
        val policy = armedPolicy(clock)

        // A message legitimately taller than the screen reports a constant height. That must not be
        // mistaken for a runaway.
        val verdicts = policy.report(clock, 1200.0, 1200.0, 1200.0, 1200.0, 1200.0)

        verdicts.forEach { it shouldBeEqualTo ModalSizeVerdict.Apply }
    }

    @Test
    fun heightReported_givenIrregularGrowthWhileLoading_expectAppliedAndNotFlagged() {
        val clock = FakeClock()
        val policy = armedPolicy(clock)

        // Images and fonts settling produce uneven growth, which is not a runaway.
        val verdicts = policy.report(clock, 200.0, 300.0, 380.0, 382.0, 382.0)

        verdicts.forEach { it shouldBeEqualTo ModalSizeVerdict.Apply }
    }

    @Test
    fun heightReported_givenNormalStableHeight_expectAppliedVerbatim() {
        val clock = FakeClock()
        val policy = armedPolicy(clock)

        val verdicts = policy.report(clock, 382.0, 382.0, 382.0, 382.0)

        verdicts.forEach { it shouldBeEqualTo ModalSizeVerdict.Apply }
    }

    @Test
    fun heightReported_givenShrinkingHeight_expectAppliedAndNotFlagged() {
        val clock = FakeClock()
        val policy = armedPolicy(clock)

        // Going back to a shorter step is a normal multi step transition.
        val verdicts = policy.report(clock, 500.0, 468.0, 436.0, 404.0)

        verdicts.forEach { it shouldBeEqualTo ModalSizeVerdict.Apply }
    }

    @Test
    fun arm_givenCalledRepeatedly_expectProgressPreserved() {
        val clock = FakeClock()
        val policy = armedPolicy(clock)

        // Callers may arm on every display state emission. Re-arming must not discard samples, or
        // the guard could be reset forever and never reach a verdict.
        val verdicts = listOf(0.0, 0.0, 0.0, 0.0).mapIndexed { index, height ->
            if (index > 0) clock.nowMs += 1000L
            policy.arm()
            policy.onHeightReported(height)
        }

        verdicts.last() shouldBeEqualTo ModalSizeVerdict.Degenerate
    }

    @Test
    fun arm_givenCalledAfterHandling_expectLatchNotSilentlyReset() {
        val clock = FakeClock()
        val policy = armedPolicy(clock)
        policy.report(clock, 0.0, 0.0, 0.0, 0.0)
        policy.onDegenerateHandled()

        policy.arm()
        val verdicts = policy.report(clock, 0.0, 0.0, 0.0)

        // Already handled, so it stays handled: no duplicate failures for the same message.
        verdicts.none { it == ModalSizeVerdict.Degenerate } shouldBeEqualTo true
    }

    @Test
    fun heightReported_givenSampleCountOfOne_expectNoCrash() {
        val clock = FakeClock()
        val policy = policy(clock, sampleCount = 1).apply { arm() }

        // Computing a growth delta needs two samples; a single sample must not index out of bounds.
        val verdicts = policy.report(clock, 500.0, 600.0)

        verdicts.size shouldBeEqualTo 2
    }
}

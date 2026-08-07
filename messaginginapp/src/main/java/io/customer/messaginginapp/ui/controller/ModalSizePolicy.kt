package io.customer.messaginginapp.ui.controller

import kotlin.math.abs

/**
 * Outcome of inspecting a height reported by the message renderer for a modal message.
 */
internal sealed interface ModalSizeVerdict {
    /**
     * Height looks sane; apply it as usual.
     */
    object Apply : ModalSizeVerdict

    /**
     * Height has stayed collapsed for long enough that the message can never become visible. The
     * modal is still on screen blocking touches, so the caller is expected to fail the message
     * rather than keep waiting.
     */
    object Degenerate : ModalSizeVerdict

    /**
     * Reported height is growing by a constant amount per report, which means the message sizes
     * itself from the WebView height that the SDK derives from it. The height is still applied, but
     * the caller should surface the cause once.
     */
    data class ViewportDependent(val deltaInDp: Double) : ModalSizeVerdict
}

/**
 * Detects message HTML whose height cannot be resolved by the SDK's content-sizing contract.
 *
 * The renderer reports the message's content height and the SDK sizes the WebView to it. When the
 * message CSS instead derives its own height from the viewport (for example `height: 100vh` or
 * `height: 100%` on `html`/`body`) the two are mutually recursive and there is no useful fixed point:
 *
 * - reported == container: every value is a fixed point, including zero. The WebView starts at zero,
 *   so the message stays collapsed forever behind a full screen, touch blocking overlay.
 * - reported == container + constant: the height grows by that constant on every report until the
 *   layout can grow no further.
 *
 * This class is deliberately free of Android dependencies so the decision logic can be unit tested
 * directly. It is only used for modal messages: inline messages legitimately report a zero height to
 * collapse themselves.
 */
internal class ModalSizePolicy(
    val degenerateMaxDp: Double = DEGENERATE_MAX_DP,
    val sampleCount: Int = SAMPLE_COUNT,
    val degenerateMinElapsedMs: Long = DEGENERATE_MIN_ELAPSED_MS,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val samples = ArrayDeque<Sample>()

    /**
     * Heights reported before the message is displayed are not evaluated: the WebView may still be
     * unlaid out and legitimately measure zero.
     */
    private var isArmed: Boolean = false
    private var hasReportedDegenerate: Boolean = false
    private var hasReportedViewportDependent: Boolean = false

    /**
     * Starts evaluating reported heights. Idempotent: repeat calls are ignored rather than discarding
     * progress, so callers that fire on every display state emission do not need to guard it, and the
     * instance can never be left half-reset.
     */
    fun arm() {
        if (isArmed) return

        isArmed = true
        samples.clear()
        hasReportedDegenerate = false
        hasReportedViewportDependent = false
    }

    fun onHeightReported(heightInDp: Double): ModalSizeVerdict {
        if (!isArmed) return ModalSizeVerdict.Apply

        samples.addLast(Sample(heightInDp = heightInDp, timestampMs = currentTimeMillis()))
        while (samples.size > sampleCount) {
            samples.removeFirst()
        }

        if (!hasReportedDegenerate && isCollapsed()) {
            return ModalSizeVerdict.Degenerate
        }

        val delta = constantGrowthDelta()
        if (!hasReportedViewportDependent && delta != null) {
            return ModalSizeVerdict.ViewportDependent(deltaInDp = delta)
        }

        return ModalSizeVerdict.Apply
    }

    /**
     * Confirms the caller acted on [ModalSizeVerdict.Degenerate] so it is not reported again. Kept
     * separate from [onHeightReported] so a verdict the caller cannot act on yet does not silently
     * disable the guard.
     */
    fun onDegenerateHandled() {
        hasReportedDegenerate = true
    }

    /**
     * Confirms the caller acted on [ModalSizeVerdict.ViewportDependent] so it is not reported again.
     */
    fun onViewportDependentHandled() {
        hasReportedViewportDependent = true
    }

    /**
     * True when every recent report is too small to show anything *and* they span enough time to
     * rule out a transient.
     *
     * Both conditions matter. `sizeChanged` is documented elsewhere in this module as arriving
     * multiple times, and the renderer also reports on every window resize, so a burst of collapsed
     * reports can arrive within milliseconds while the message is merely still being laid out.
     */
    private fun isCollapsed(): Boolean {
        if (samples.size < sampleCount) return false
        if (samples.any { it.heightInDp > degenerateMaxDp }) return false

        val elapsed = samples.last().timestampMs - samples.first().timestampMs
        return elapsed >= degenerateMinElapsedMs
    }

    /**
     * Returns the shared delta when recent reports grow by the same positive amount each time,
     * otherwise null. A message whose height legitimately settles while images and fonts load grows
     * by irregular amounts, so requiring a constant delta avoids flagging it.
     */
    private fun constantGrowthDelta(): Double? {
        if (samples.size < sampleCount || samples.size < MIN_SAMPLES_FOR_GROWTH) return null

        val heights = samples.map { it.heightInDp }
        val first = heights[1] - heights[0]
        if (first <= 0.0) return null

        for (index in 2 until heights.size) {
            val delta = heights[index] - heights[index - 1]
            if (abs(delta - first) > DELTA_TOLERANCE_DP) return null
        }
        return first
    }

    private data class Sample(val heightInDp: Double, val timestampMs: Long)

    companion object {
        /**
         * Heights at or below this are treated as collapsed. No message can render usable content in
         * this space, and it covers the values seen in the field: zero, and the few points left by a
         * collapsed default body margin.
         */
        const val DEGENERATE_MAX_DP: Double = 20.0

        /**
         * Reports needed before a collapsed verdict is possible.
         */
        const val SAMPLE_COUNT: Int = 4

        /**
         * How long the collapsed reports must span. The renderer polls about once per second, so
         * this clears a burst of reports triggered by layout without waiting much beyond the poll.
         */
        const val DEGENERATE_MIN_ELAPSED_MS: Long = 2500

        /**
         * At least two heights are needed to compute a delta at all.
         */
        private const val MIN_SAMPLES_FOR_GROWTH: Int = 2

        /**
         * Growth deltas are compared with a small tolerance because reported heights are rounded
         * from CSS pixels.
         */
        private const val DELTA_TOLERANCE_DP: Double = 0.5
    }
}

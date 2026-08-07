package io.customer.messaginginapp.ui.controller

import kotlin.math.abs

/**
 * Outcome of inspecting a height reported by the message renderer for a modal message.
 */
internal sealed interface ModalSizeVerdict {
    /**
     * Height looks sane; apply it as usual.
     */
    data class Apply(val heightInDp: Double) : ModalSizeVerdict

    /**
     * Height has stayed collapsed for [ModalSizePolicy.SAMPLE_COUNT] consecutive reports, so the
     * message can never become visible. The modal is still on screen blocking touches, so the
     * caller is expected to fail the message rather than keep waiting.
     */
    object Degenerate : ModalSizeVerdict

    /**
     * Reported height is growing by a constant amount per report, which means the message sizes
     * itself from the WebView height that the SDK derives from it. The height is still applied
     * (the container clamps it), but the caller should surface the cause once.
     */
    data class ViewportDependent(
        val heightInDp: Double,
        val deltaInDp: Double
    ) : ModalSizeVerdict
}

/**
 * Detects message HTML whose height cannot be resolved by the SDK's content-sizing contract.
 *
 * The renderer reports the message's content height roughly once per second and the SDK sizes the
 * WebView to it. When the message CSS instead derives its own height from the viewport (for example
 * `height: 100vh` or `height: 100%` on `html`/`body`) the two are mutually recursive and there is no
 * useful fixed point:
 *
 * - reported == container: every value is a fixed point, including zero. The WebView starts at zero,
 *   so the message stays collapsed forever behind a full screen, touch blocking overlay.
 * - reported == container + constant: the height grows by that constant on every report until the
 *   container clamps it, and the message renders at full screen with its content pinned to the top.
 *
 * This class is deliberately free of Android dependencies so the decision logic can be unit tested
 * directly. It is only used for modal messages: inline messages legitimately report a zero height to
 * collapse themselves.
 */
internal class ModalSizePolicy(
    private val degenerateMaxDp: Double = DEGENERATE_MAX_DP,
    private val sampleCount: Int = SAMPLE_COUNT
) {
    private val samples = ArrayDeque<Double>()

    /**
     * Heights reported before the message is displayed are not evaluated: the WebView is still
     * detached and legitimately measures zero while it loads.
     */
    private var isArmed: Boolean = false
    private var hasReportedDegenerate: Boolean = false
    private var hasReportedViewportDependent: Boolean = false

    /**
     * Starts evaluating reported heights. Called once the message is displayed.
     */
    fun arm() {
        isArmed = true
        samples.clear()
    }

    fun onHeightReported(heightInDp: Double): ModalSizeVerdict {
        if (!isArmed) return ModalSizeVerdict.Apply(heightInDp)

        samples.addLast(heightInDp)
        while (samples.size > sampleCount) {
            samples.removeFirst()
        }

        if (!hasReportedDegenerate && isCollapsed()) {
            hasReportedDegenerate = true
            return ModalSizeVerdict.Degenerate
        }

        val delta = constantGrowthDelta()
        if (!hasReportedViewportDependent && delta != null) {
            hasReportedViewportDependent = true
            return ModalSizeVerdict.ViewportDependent(heightInDp = heightInDp, deltaInDp = delta)
        }

        return ModalSizeVerdict.Apply(heightInDp)
    }

    /**
     * True when every recent report is too small to show anything. A single small report is not
     * enough: a multi step message can momentarily measure small while switching steps.
     */
    private fun isCollapsed(): Boolean {
        return samples.size >= sampleCount && samples.all { it <= degenerateMaxDp }
    }

    /**
     * Returns the shared delta when recent reports grow by the same positive amount each time,
     * otherwise null. A message whose height legitimately settles while images and fonts load grows
     * by irregular amounts, so requiring a constant delta avoids flagging it.
     */
    private fun constantGrowthDelta(): Double? {
        if (samples.size < sampleCount) return null

        val heights = samples.toList()
        val first = heights[1] - heights[0]
        if (first <= 0.0) return null

        for (index in 2 until heights.size) {
            val delta = heights[index] - heights[index - 1]
            if (abs(delta - first) > DELTA_TOLERANCE_DP) return null
        }
        return first
    }

    companion object {
        /**
         * Heights at or below this are treated as collapsed. No message can render usable content in
         * this space, and it covers the values seen in the field: zero, and the few points left by a
         * collapsed default body margin.
         */
        const val DEGENERATE_MAX_DP: Double = 20.0

        /**
         * Reports needed before a verdict is returned. The renderer reports about once per second.
         */
        const val SAMPLE_COUNT: Int = 4

        /**
         * Growth deltas are compared with a small tolerance because reported heights are rounded
         * from CSS pixels.
         */
        private const val DELTA_TOLERANCE_DP: Double = 0.5
    }
}

package io.customer.geofence.replay

import io.customer.sdk.core.util.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred

/** The SDK's clock, driven by the scenario's elapsed time. */
internal class VirtualClock(private val epochMillisAtStart: Long = 1_756_000_000_000L) : Clock {
    /** Seconds since the scenario's `t0`. */
    var elapsedSeconds: Double = 0.0

    /**
     * Well above zero: a stored registration uptime above "now" reads as a reboot. A `var` so
     * [ReplayRunner] can lift it to Robolectric's SystemClock, keeping both clocks on one origin.
     */
    var uptimeBaseMillis = TimeUnit.HOURS.toMillis(6)

    override fun currentTimeMillis(): Long = epochMillisAtStart + (elapsedSeconds * 1000).toLong()

    override fun currentTimeSeconds(): Long = TimeUnit.MILLISECONDS.toSeconds(currentTimeMillis())

    override fun elapsedRealtime(): Long = uptimeBaseMillis + (elapsedSeconds * 1000).toLong()
}

/**
 * Holds a coroutine until the drive's clock reaches the moment the real boundary (network, Play
 * Services) answered. Answering instantly would leave no window for a callback to land mid-sync.
 */
internal class ReplayBoundaryGate(val clock: VirtualClock) {

    private class Parked(val releaseAt: Double, val what: String, val gate: CompletableDeferred<Unit>)

    private val parked = mutableListOf<Parked>()

    /** Boundaries still waiting when the scenario ended. Diagnostic, not a failure. */
    var abandonedAtEnd: List<String> = emptyList()
        private set

    /** An already-due boundary must not park: that keeps [advanceTo] from looping on a re-park. */
    suspend fun awaitVirtual(releaseAt: Double, what: String) {
        if (releaseAt <= clock.elapsedSeconds) return
        val deferred = CompletableDeferred<Unit>()
        val boundary = Parked(releaseAt, what, deferred)
        parked += boundary
        try {
            deferred.await()
        } finally {
            // A dead process cannot leave an answer that advances the new process's clock.
            parked.remove(boundary)
        }
    }

    /**
     * Releases each boundary at its own recorded moment, not all at [target], which would date the
     * unblocked work to the next input. Re-scans after each release, since one often parks the next.
     */
    fun advanceTo(target: Double, pump: () -> Unit) {
        var guard = 0
        while (true) {
            val next = parked.filter { it.releaseAt <= target }.minByOrNull { it.releaseAt } ?: break
            check(guard++ < MAX_RELEASE_ROUNDS) {
                "replay: boundary releases did not settle after $MAX_RELEASE_ROUNDS rounds (${next.what})"
            }
            parked.remove(next)
            // Never backwards: boundaries can share a moment, or sit fractionally behind the clock.
            clock.elapsedSeconds = maxOf(clock.elapsedSeconds, next.releaseAt)
            next.gate.complete(Unit)
            pump()
        }
        clock.elapsedSeconds = maxOf(clock.elapsedSeconds, target)
    }

    /** Answers whatever is still parked so the run finishes; a capture can end mid-sync. */
    fun releaseAll(pump: () -> Unit) {
        var guard = 0
        val abandoned = mutableListOf<String>()
        while (parked.isNotEmpty()) {
            check(guard++ < MAX_RELEASE_ROUNDS) { "replay: boundary releases did not settle at end of scenario" }
            val next = parked.minByOrNull { it.releaseAt } ?: break
            parked.remove(next)
            abandoned += next.what
            clock.elapsedSeconds = maxOf(clock.elapsedSeconds, next.releaseAt)
            next.gate.complete(Unit)
            pump()
        }
        abandonedAtEnd = abandoned
    }

    fun virtualNow(): Double = clock.elapsedSeconds

    /** Between scenarios: a boundary parked by the previous drive would resume inside the next. */
    fun reset() {
        parked.toList().forEach { it.gate.complete(Unit) }
        parked.clear()
        abandonedAtEnd = emptyList()
    }

    companion object {
        /**
         * Fallback Play Services round trip. No constant puts a callback on the right side of a
         * registration, so [ReplayRegistrar] uses this only when no recorded answer is left.
         */
        const val REGISTRAR_LATENCY_SECONDS: Double = 0.040

        private const val MAX_RELEASE_ROUNDS = 64
    }
}

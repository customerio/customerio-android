package io.customer.geofence.replay

import io.customer.sdk.core.util.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred

/**
 * Wall time under the scenario's control. The SDK's time-dependent rules (cooldown, freshness, the
 * reboot check) read the recorded drive's elapsed seconds through this.
 */
internal class VirtualClock(private val epochMillisAtStart: Long = 1_756_000_000_000L) : Clock {
    /** Seconds since the scenario's `t0`. */
    var elapsedSeconds: Double = 0.0

    /**
     * Starts well above zero: the SDK detects a reboot as a stored registration uptime above "now",
     * so zero would make every replay look like a fresh boot. A `var` so [ReplayRunner] can lift it
     * to Robolectric's SystemClock at a drive's start, keeping both clocks on one origin.
     */
    var uptimeBaseMillis = TimeUnit.HOURS.toMillis(6)

    override fun currentTimeMillis(): Long = epochMillisAtStart + (elapsedSeconds * 1000).toLong()

    override fun currentTimeSeconds(): Long = TimeUnit.MILLISECONDS.toSeconds(currentTimeMillis())

    override fun elapsedRealtime(): Long = uptimeBaseMillis + (elapsedSeconds * 1000).toLong()
}

/**
 * Holds a coroutine until the drive's clock reaches the moment the real boundary (network, Play
 * Services) answered.
 *
 * A double that answers instantly closes the window in which the OS can deliver a callback
 * mid-sync, so no input that arrives while the SDK is mid-reaction could be replayed. Nothing
 * sleeps: [VirtualClock] moves only as the runner reaches recorded records, so the outcome depends
 * on the recording, not on machine speed.
 */
internal class ReplayBoundaryGate(val clock: VirtualClock) {

    private class Parked(val releaseAt: Double, val what: String, val gate: CompletableDeferred<Unit>)

    private val parked = mutableListOf<Parked>()

    /** Boundaries the scenario ended while still waiting on. Diagnostic, not a failure. */
    var abandonedAtEnd: List<String> = emptyList()
        private set

    /**
     * Suspends the caller until virtual time reaches [releaseAt].
     *
     * A boundary whose answer is already due does not park at all, which is what keeps
     * [advanceTo] from looping: a coroutine resumed inside it can only re-park in the future.
     */
    suspend fun awaitVirtual(releaseAt: Double, what: String) {
        if (releaseAt <= clock.elapsedSeconds) return
        val deferred = CompletableDeferred<Unit>()
        parked += Parked(releaseAt, what, deferred)
        deferred.await()
    }

    /**
     * Runs the clock forward to [target], releasing each boundary at its own recorded moment.
     *
     * Releasing everything at [target] would date the work a boundary unblocks to the next input,
     * which would then meet a world that had not reacted yet. The list is re-examined after each
     * release because one often parks the next (a fetch resuming into a registration), and [pump]
     * runs what a release made runnable before the next is considered.
     */
    fun advanceTo(target: Double, pump: () -> Unit) {
        var guard = 0
        while (true) {
            val next = parked.filter { it.releaseAt <= target }.minByOrNull { it.releaseAt } ?: break
            check(guard++ < MAX_RELEASE_ROUNDS) {
                "replay: boundary releases did not settle after $MAX_RELEASE_ROUNDS rounds (${next.what})"
            }
            parked.remove(next)
            // Never backwards: two boundaries can answer at the same recorded moment, and a
            // recorded time can sit fractionally before where the clock already stands.
            clock.elapsedSeconds = maxOf(clock.elapsedSeconds, next.releaseAt)
            next.gate.complete(Unit)
            pump()
        }
        clock.elapsedSeconds = maxOf(clock.elapsedSeconds, target)
    }

    /**
     * Ends the drive, answering whatever is still outstanding so the run finishes rather than hangs.
     *
     * A capture can legitimately end mid-sync. The abandoned boundaries are kept in [abandonedAtEnd]
     * because they explain expectations the matcher can only report as "never happened".
     */
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

    /** Virtual seconds since the drive's `t0`, for a boundary whose latency is a duration. */
    fun virtualNow(): Double = clock.elapsedSeconds

    /** Between scenarios: a boundary parked by the previous drive would resume inside the next. */
    fun reset() {
        parked.forEach { it.gate.complete(Unit) }
        parked.clear()
        abandonedAtEnd = emptyList()
    }

    companion object {
        /**
         * Fallback for a Play Services round trip the recording does not cover. Real round trips
         * vary too much for any constant to put a callback on the right side of a registration, so
         * [ReplayRegistrar] uses the capture's answer times and falls back here only when none is left.
         */
        const val REGISTRAR_LATENCY_SECONDS: Double = 0.040

        private const val MAX_RELEASE_ROUNDS = 64
    }
}

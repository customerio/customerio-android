package io.customer.geofence.replay

import io.customer.sdk.core.util.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred

/**
 * Wall time and the async boundaries a replay controls.
 *
 * Both exist so a scenario can say when something happens. Without them a replay could only assert
 * what fits inside one wall-clock second, and every boundary would resolve instantly — which is
 * exactly the window several SDK rules are about.
 */

/**
 * Wall time, under the scenario's control.
 *
 * The recorded drive supplies elapsed seconds; everything time-dependent in the SDK — the cooldown
 * window, the freshness window, the reboot check — reads them through this. Without it a replay
 * could only assert things that happen inside one wall-clock second.
 */
internal class VirtualClock(private val epochMillisAtStart: Long = 1_756_000_000_000L) : Clock {
    /** Seconds since the scenario's `t0`. */
    var elapsedSeconds: Double = 0.0

    /**
     * Uptime starts well above zero so the reboot check behaves as it would on a running device: a
     * stored registration uptime greater than "now" is how the SDK detects a reboot, and starting
     * at zero would make every replay look like a fresh boot.
     */
    private val uptimeBaseMillis = TimeUnit.HOURS.toMillis(6)

    override fun currentTimeMillis(): Long = epochMillisAtStart + (elapsedSeconds * 1000).toLong()

    override fun currentTimeSeconds(): Long = TimeUnit.MILLISECONDS.toSeconds(currentTimeMillis())

    override fun elapsedRealtime(): Long = uptimeBaseMillis + (elapsedSeconds * 1000).toLong()
}

/**
 * Holds a coroutine until the drive's clock reaches the moment the real boundary answered.
 *
 * The doubles below stand where the network and Play Services stand. Until now they were faithful
 * in *value* and instantaneous in *time*; the real ones were neither, and the difference is not
 * cosmetic. On the 2026-09-10 S24 drive the fetch took 749 ms and the registrar round trip about
 * 40 ms, and the OS delivered an enter for one fence **inside** that window:
 *
 * ```
 * when  8.155  location.fix prov=bus          ← sync starts
 * given 8.919  fixture.api.fetch              ← network answers, 764 ms later
 * note  8.943  registration.removed
 * when  8.968  os.callback enter              ← lands mid-sync
 * then  8.969  transition.dropped why=unknown_id
 * note  8.981  registration.added
 * then  9.002  registration.applied
 * then  9.007  transition.synthesized why=initial_enter_inside
 * ```
 *
 * A double that answers instantly closes that window before the callback can land in it, so the
 * replay met a fully-registered world and dropped the same crossing as `already_reported` instead.
 * Every input that arrives while the SDK is mid-reaction is unreachable that way — and on a
 * geofencing SDK that is most of the interesting behaviour.
 *
 * **Nothing sleeps.** Release is driven by [VirtualClock], which only moves when the runner reaches
 * the next recorded record, so the outcome depends on the recording rather than on how fast the
 * machine runs. That is the difference between this and replaying the gaps in real time.
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
     * Runs the clock forward to [target], answering each boundary **at its own moment** on the way.
     *
     * Advancing straight to the target and releasing everything there would date all the work a
     * boundary unblocks to whenever the next stimulus happened to be. On the 2026-09-10 Pixel drive
     * the network answered at 12.121 and the next recorded input was at 12.334: releasing at the
     * target put 213 ms of the SDK's reaction — ranking, the whole registration round trip — into
     * the same instant as the input it was supposed to precede, and the input met a world that had
     * not registered anything yet.
     *
     * So each release steps the clock to the moment that boundary actually answered, and the
     * coroutine it resumes reads that time. Releasing one usually reaches the next straight away —
     * a fetch resuming into a registration — so the list is re-examined rather than snapshotted,
     * and [pump] runs whatever the release made runnable before the next one is considered.
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
     * Ends the drive: answers whatever is still outstanding so the run finishes rather than hangs.
     *
     * A capture can legitimately end mid-sync — the recording simply stops — so this is not an
     * error. It is recorded because "the replay ended with three boundaries in flight" explains a
     * class of missing expectation that the matcher can only report as "never arrived".
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
         * Fallback for a Play Services round trip the recording does not cover.
         *
         * A modelled constant was the first attempt and it is not good enough: the drives disagree
         * by far more than any single figure absorbs. On the 2026-09-10 S24 the OS delivered an
         * enter 25 ms into a 59 ms registration and the SDK dropped it as `unknown_id`; on the
         * Pixel the same drive delivered one 41 ms *after* a 155 ms registration had landed and the
         * SDK accepted it. A constant places the input on the wrong side of one of them whichever
         * value it takes, so [ReplayRegistrar] reads the moment out of the capture and only falls
         * back here when a call has no recorded answer left to match.
         */
        const val REGISTRAR_LATENCY_SECONDS: Double = 0.040

        private const val MAX_RELEASE_ROUNDS = 64
    }
}

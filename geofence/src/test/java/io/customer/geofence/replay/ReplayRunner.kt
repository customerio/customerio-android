package io.customer.geofence.replay

import android.os.SystemClock
import io.customer.geofence.GeofenceCrossingPipeline
import io.customer.geofence.GeofenceForegroundCoordinator
import io.customer.geofence.GeofenceServices
import io.customer.geofence.polygon.PolygonPassiveReceiver
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.robolectric.shadows.ShadowSystemClock

/**
 * Feeds a scenario's inputs into a composed SDK in recorded order, each through the door the OS or
 * host app uses. Network and Play Services calls park on [ReplayBoundaryGate] until their recorded
 * moments, so the recording decides how far the SDK has got when each input arrives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ReplayRunner(
    private val gate: ReplayBoundaryGate,
    private val scheduler: TestCoroutineScheduler,
    private val geofenceScope: CoroutineScope,
    private val api: ReplayApiService,
    private val registrar: ReplayRegistrar,
    private val pipeline: GeofenceCrossingPipeline,
    private val services: GeofenceServices,
    private val foreground: GeofenceForegroundCoordinator,
    private val identity: MutableIdentity,
    private val freshFix: ReplayPolygonFreshFixSource,
    private val assertDelivery: (ScenarioRecord) -> String? = { "delivery.queued has no outbox assertion seam" }
) {
    /** A stimulus the scenario carries that this composition has nowhere to put. */
    data class Unsupported(val ev: String, val at: Double)

    /**
     * [unanswered]: recorded `polygon.freshfix.received` times no open request was waiting for, i.e.
     * a request the replay never made (unlike [unsupported], a missing seam).
     */
    data class Result(
        val unsupported: List<Unsupported>,
        val unanswered: List<Double> = emptyList(),
        val assertionFailures: List<String> = emptyList()
    )

    /** Last recorded position. Not read: identify passes no position (see `identity.changed`). */
    private var lastFix: Pair<Double, Double>? = null

    /** Drive time the coroutine scheduler has been carried to. */
    private var pumpedThrough: Double = 0.0

    suspend fun run(scenario: Scenario): Result {
        // The SDK ages fixes on SystemClock and they are stamped on this clock; start both on one
        // origin.
        gate.clock.uptimeBaseMillis = maxOf(gate.clock.uptimeBaseMillis, SystemClock.elapsedRealtime())
        syncSystemClock()

        val unsupported = mutableListOf<Unsupported>()
        val unanswered = mutableListOf<Double>()
        val assertionFailures = mutableListOf<String>()

        // Queued up front, not on the timeline: a fixture's `at` is its release time (see
        // ReplayApiService).
        scenario.given
            .filter { it.ev == "fixture.api.fetch" }
            .sortedBy { it.at }
            .forEach { api.enqueue(it) }

        registrar.loadAnswerTimes(
            added = scenario.records.filter { it.ev == "registration.added" }.map { it.at },
            removed = scenario.records.filter { it.ev == "registration.removed" }.map { it.at },
            cleared = scenario.records.filter { it.ev == "registration.cleared" }.map { it.at }
        )

        val timeline = scenario.records.filter {
            it.kind == Scenario.Kind.WHEN || (it.kind == Scenario.Kind.THEN && it.ev == ReplayDeliveryAssertions.EVENT)
        }
        for (record in timeline) {
            gate.advanceTo(record.at) { pump() }
            // The gate reaches `record.at` after its last pump, so pump again: work due by then
            // runs first.
            pump()
            if (record.kind == Scenario.Kind.THEN) {
                assertDelivery(record)?.let(assertionFailures::add)
                continue
            }
            when (record.ev) {
                // Process lifecycle: module wiring is not what a drive exercises.
                "process.start", "module.init", "module.wake" -> Unit

                // Real external inputs this composition does not model; accepted as no-ops.
                "permission.changed", "os.error" -> Unit

                "app.foreground" -> foreground.onForeground()

                // Backgrounding only cancels an in-flight fix request, and the harness never holds one.
                "app.background" -> Unit

                "identity.changed" -> {
                    val signedIn = record.boolean("ok") ?: true
                    if (signedIn) {
                        identity.userId = REPLAY_USER_ID
                        // No position: on device the read at identify comes back empty and the next
                        // `prov=bus` fix drives the sync. A remembered fix could rank from a stale
                        // place.
                        services.onUserIdentified(null, null)
                    } else {
                        identity.userId = null
                        services.onUserSignedOut()
                    }
                }

                "location.fix" -> {
                    val lat = record.double("lat")
                    val lon = record.double("lon")
                    if (lat != null && lon != null) {
                        lastFix = lat to lon
                        // Only `prov=bus` is a fix the Location module delivered. Other sources are
                        // reads the SDK made, and feeding one back would invent a location update.
                        if (record.string("prov") == ARRIVAL_FIX_SOURCE) {
                            services.onLocationAcquired(lat, lon)
                        }
                    }
                }

                "os.callback" -> {
                    val crossing = record.toCrossing(gate.clock.currentTimeSeconds(), gate.clock.elapsedRealtime())
                    crossing.latitude?.let { lat ->
                        crossing.longitude?.let { lon -> lastFix = lat to lon }
                    }
                    // On the geofence scope: the pipeline removes orphans inline, so on the runner's
                    // coroutine it would park the runner on a boundary only the runner can open.
                    geofenceScope.launch { pipeline.handle(crossing) }
                }

                // `triggeringFix` dates it from the recorded age, so the same cached fix the
                // callback carried keeps the same `elapsedRealtimeNanos`.
                "polygon.freshfix.received" ->
                    record.triggeringFix(gate.clock.elapsedRealtime())?.let { fix ->
                        if (!freshFix.deliver(fix)) unanswered.add(record.at)
                    }

                // A fix another app requested, on the geofence scope as `PolygonPassiveReceiver`
                // runs it.
                "polygon.passive.received" ->
                    record.triggeringFix(gate.clock.elapsedRealtime())?.let { fix ->
                        geofenceScope.launch { PolygonPassiveReceiver().handleFix(fix) }
                    }

                // The re-check requests its own fix, so the fix it logs is an answer, not a stimulus.
                "polygon.recheck.ran" -> Unit

                else -> unsupported.add(Unsupported(record.ev, record.at))
            }
            pump()
        }

        // So decisions still in flight when the capture ended are graded rather than lost.
        gate.releaseAll { pump() }
        pump()
        return Result(unsupported, unanswered, assertionFailures)
    }

    /**
     * The gate advances [VirtualClock], but `runCurrent()` drains only what is due on the scheduler's
     * own clock, so the scheduler advances by the same amount; otherwise a `delay()` never resumes.
     */
    private fun pump() {
        val now = gate.virtualNow()
        val advanceMillis = ((now - pumpedThrough) * 1000).toLong()
        // `advanceTimeBy` stops short of the instant it lands on; `runCurrent` takes what is due there.
        // Whole milliseconds only, so sub-millisecond gaps accumulate instead of rounding to zero.
        if (advanceMillis > 0) {
            scheduler.advanceTimeBy(advanceMillis)
            pumpedThrough += advanceMillis / 1000.0
        }
        syncSystemClock()
        scheduler.runCurrent()
    }

    /**
     * The SDK ages fixes and times polygon-approach sessions on SystemClock, not the injected `Clock`.
     * Left behind, every replayed fix would read hours old and the polygon path would drop it.
     */
    private fun syncSystemClock() {
        val target = gate.clock.elapsedRealtime()
        val now = SystemClock.elapsedRealtime()
        if (target > now) ShadowSystemClock.advanceBy(Duration.ofMillis(target - now))
    }

    /** The identity the host app would have set. */
    internal class MutableIdentity {
        var userId: String? = null
    }

    private companion object {
        // Not the recorded user: captures carry no identifier.
        const val REPLAY_USER_ID = "replay-user"

        // `GeofenceLogTail.FixSource` has no member for it: `GeofenceLogger` logs it as a literal.
        const val ARRIVAL_FIX_SOURCE = "bus"
    }
}

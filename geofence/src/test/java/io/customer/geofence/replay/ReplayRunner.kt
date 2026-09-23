package io.customer.geofence.replay

import io.customer.geofence.GeofenceCrossingPipeline
import io.customer.geofence.GeofenceForegroundCoordinator
import io.customer.geofence.GeofenceServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Feeds a scenario's inputs into a composed SDK, in recorded order, on a virtual clock.
 *
 * Every stimulus below enters through the same door the OS or host app uses in production. There is
 * no path here that reaches inside the SDK to arrange state: a replay that needed one would be
 * proving something about the harness rather than the SDK.
 *
 * **The SDK is not run to stillness between stimuli.** It used to be — every double answered
 * instantly, so `services.onLocationAcquired(...)` returned only once the whole sync had finished,
 * and the next stimulus always met a settled world. The road does not work that way: a sync spans
 * a network round trip and a Play Services round trip, and OS callbacks land inside them. Those two
 * boundaries now park on the virtual clock (see [ReplayBoundaryGate]), and this loop opens them at
 * each recorded moment instead of letting them answer for free. What the SDK has finished by the
 * time a stimulus arrives is therefore decided by the recording, not by the harness.
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
    private val identity: MutableIdentity
) {
    /** A stimulus the scenario carries that this composition has nowhere to put. */
    data class Unsupported(val ev: String, val at: Double)

    data class Result(val unsupported: List<Unsupported>)

    /** Where the SDK's own view of "the last position" comes from, for identity-driven syncs. */
    private var lastFix: Pair<Double, Double>? = null

    /** How far along the drive's timeline the coroutine scheduler has been carried. See [pump]. */
    private var pumpedThrough: Double = 0.0

    suspend fun run(scenario: Scenario): Result {
        val unsupported = mutableListOf<Unsupported>()

        // Fixtures are queued up front, in recorded order, rather than placed on the timeline.
        //
        // `api.fetch.result` is stamped when the *response arrived*, not when the request went out
        // — a drive with a 1.1 s round trip records the fixture a second after the sync that asked
        // for it, so a timeline-ordered replay starves its own first fetch and registers nothing.
        // The duration is on the record as `ms`, but the transform strips it as volatile, so it
        // cannot be subtracted back out.
        //
        // Queuing them up front is also the faithful reading: a `given` is the world the SDK is
        // dropped into, not an event it reacts to, which is why the transform buckets it apart from
        // the stimuli. Order is preserved, so the nth fetch still gets the nth recorded response.
        //
        // The timestamp is not discarded, though. `ReplayApiService` keeps it as the moment the
        // response *may be handed over*, so the nth fetch parks until the clock reaches the nth
        // recorded arrival — the round trip the capture measured, spent in virtual time.
        scenario.given
            .filter { it.ev == "fixture.api.fetch" }
            .sortedBy { it.at }
            .forEach { api.enqueue(it) }

        // When Play Services answered, taken from the drive rather than modelled. See
        // `ReplayRegistrar.loadAnswerTimes` for why a constant cannot stand in for these.
        registrar.loadAnswerTimes(
            added = scenario.records.filter { it.ev == "registration.added" }.map { it.at },
            removed = scenario.records.filter { it.ev == "registration.removed" }.map { it.at },
            // `clearAll` logs `registration.cleared`, not `registration.removed`. Supplying these
            // is what stops a sign-out clear from drawing a moment out of the remove pool — and
            // from falling back to the modelled latency, which on one recorded drive is 89 ms
            // earlier than the clear actually answered.
            cleared = scenario.records.filter { it.ev == "registration.cleared" }.map { it.at }
        )

        for (record in scenario.stimuli) {
            // Runs the clock up to this input, answering on the way every boundary that answered
            // before it did. Releasing afterwards would deliver the input into the older world; not
            // releasing at all is the settle-to-stillness model this replaces.
            gate.advanceTo(record.at) { pump() }
            // The gate sets the clock to `record.at` only after its last release, so one more pass
            // is needed to carry the scheduler the rest of the way — otherwise work the SDK asked
            // to be woken for *before* this input would run after it.
            pump()
            when (record.ev) {
                // Process lifecycle. Recorded so a capture is distinguishable from a process the OS
                // never started; nothing in this composition reacts to them, which is deliberate —
                // module wiring is not what a drive exercises.
                "process.start", "module.init", "module.wake" -> Unit

                // A genuine external input that reaches nothing here. Accepted rather than reported
                // so the funnel stays honest about what it does and does not drive.
                "permission.changed", "os.error" -> Unit

                // Straight into the real coordinator. An earlier version of this branch
                // re-implemented its gates here and got them wrong twice — it dropped the
                // `locationMode` check and the whole retry fallback — which is the exact failure
                // the extractions exist to prevent. The SDK decides; replay only says when the app
                // came forward.
                "app.foreground" -> foreground.onForeground()

                // Backgrounding cancels an in-flight fix request. Nothing in this composition holds
                // one — the harness has no location provider, fixes arrive as stimuli — so there is
                // nothing to cancel. Accepted so the record is not reported as unsupported, and
                // noted here so the gap is a decision rather than an oversight.
                "app.background" -> Unit

                "identity.changed" -> {
                    val signedIn = record.boolean("ok") ?: true
                    if (signedIn) {
                        identity.userId = REPLAY_USER_ID
                        // NO position. Production reads the Location module here, and in the app
                        // every drive is captured from that read comes back empty: every single
                        // `identity.changed ok=true` in the corpus is followed by
                        // `sync.skipped why=no_location ctx=user_identified`, then a `prov=bus`
                        // fix a fraction of a second later that drives the sync through
                        // `onLocationAcquired`. Handing over `lastFix` instead skipped that path
                        // and synced from a remembered position.
                        //
                        // Harmless until a drive signed out: on 2026-09-12 the re-login came 3 h 41 m
                        // and several km after the last fix, so replay ranked from there and
                        // synthesized the initial enter for the wrong fence. iOS had the same bug
                        // by a different route (customerio-ios 046e22a7).
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
                        // Only an ARRIVAL is handed over. `prov=bus` is the fix the Location module
                        // delivered; every other source names a read the SDK went and made, which
                        // is ambient state, not an event — feeding one back as an arrival invents a
                        // location update that never happened. Every recorded Android drive is
                        // `bus`, so this changes nothing there; it matters for an authored scenario
                        // that also carries the cached reads the iOS composition needs.
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
                    // On the geofence scope, where `GeofenceBroadcastReceiver` runs it — not inline
                    // in the runner's own coroutine.
                    //
                    // Two reasons, and the second only became visible once the doubles could park.
                    // It is what production does: the receiver takes a `goAsync` window and calls
                    // `scopeProvider.geofenceScope.launch { … handle(crossing) }`. And the pipeline
                    // tears down orphan registrations *inline* — a crossing for an unknown fence
                    // calls `removeGeofencesByIds` on the caller's coroutine. Run inline here, that
                    // parks the runner on a boundary only the runner can open.
                    geofenceScope.launch { pipeline.handle(crossing) }
                }

                else -> unsupported.add(Unsupported(record.ev, record.at))
            }
            // Whatever the stimulus started runs as far as it can get; where it reaches a boundary
            // it parks there, and stays parked until a later record's timestamp opens it.
            pump()
        }

        // The recording stops but the SDK does not: a capture can end with a sync in flight. Let
        // the outstanding boundaries answer so those decisions are graded rather than lost.
        gate.releaseAll { pump() }
        pump()
        return Result(unsupported)
    }

    /**
     * Runs whatever the SDK can now run, on the drive's clock.
     *
     * **Two clocks used to run here and only one of them moved.** [ReplayBoundaryGate] advances the
     * [VirtualClock] — the drive's timeline, which everything time-dependent in the SDK reads —
     * while `runCurrent()` drains only what is already due on the *scheduler's* clock, and nothing
     * ever advanced that one. So a `delay()` parked forever. Production's movement pass waits for
     * the sync slot rather than dropping it (`GeofenceRepository.awaitRefreshSlot`, 50 ms polls up
     * to 30 s), and on the 2026-09-12 S24 drive a live fix and a movement-trigger exit arrived 17 ms
     * apart: the device ran both passes 81 ms apart, replay ran one and lost a local re-rank.
     *
     * Carrying the scheduler forward by however far the drive's clock has moved makes a recorded
     * wait cost recorded time. Nothing sleeps — both clocks are numbers.
     */
    private fun pump() {
        // By how much the drive's clock has moved, not where it now stands: one scheduler is shared
        // by every drive in a run, so its absolute time is the sum of the drives before this one
        // while `gate.virtualNow()` restarts at each drive's `t0`. Only the delta is meaningful.
        val now = gate.virtualNow()
        val advanceMillis = ((now - pumpedThrough) * 1000).toLong()
        // `advanceTimeBy` stops short of the instant it lands on; `runCurrent` takes what is due
        // there. Below a millisecond it does nothing, so `pumpedThrough` only moves when it does —
        // otherwise a burst of sub-millisecond stimuli would round each gap to zero and lose them all.
        if (advanceMillis > 0) {
            scheduler.advanceTimeBy(advanceMillis)
            pumpedThrough += advanceMillis / 1000.0
        }
        scheduler.runCurrent()
    }

    /** The identity the host app would have set. Held by the harness, read by the SDK. */
    internal class MutableIdentity {
        var userId: String? = null
    }

    private companion object {
        // Not the recorded user: captures carry no identifier, by design.
        const val REPLAY_USER_ID = "replay-user"

        // `GeofenceLogTail.FixSource` has no member for it: the bus fix is logged by
        // `GeofenceLogger` as a literal, and iOS spells the same channel the same way.
        const val ARRIVAL_FIX_SOURCE = "bus"
    }
}

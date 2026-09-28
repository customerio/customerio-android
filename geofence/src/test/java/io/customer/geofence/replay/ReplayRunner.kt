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
 * Feeds a scenario's inputs into a composed SDK, in recorded order, on a virtual clock.
 *
 * Every stimulus enters through the door the OS or host app uses in production; nothing reaches in
 * to arrange state. The SDK is not run to stillness between stimuli: network and Play Services
 * calls park on [ReplayBoundaryGate] and are released at their recorded moments, so the recording
 * decides how far the SDK has got when each input arrives.
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
    private val freshFix: ReplayPolygonFreshFixSource
) {
    /** A stimulus the scenario carries that this composition has nowhere to put. */
    data class Unsupported(val ev: String, val at: Double)

    /**
     * [unanswered]: recorded `polygon.freshfix.received` timestamps no open request was waiting for.
     * Kept apart from [unsupported], which is a missing seam; this is a request the replay never made.
     */
    data class Result(val unsupported: List<Unsupported>, val unanswered: List<Double> = emptyList())

    /** Last recorded position. Not read: identify passes no position (see `identity.changed`). */
    private var lastFix: Pair<Double, Double>? = null

    /** How far along the drive's timeline the coroutine scheduler has been carried. See [pump]. */
    private var pumpedThrough: Double = 0.0

    suspend fun run(scenario: Scenario): Result {
        // Start the drive's uptime line at (or above) Robolectric's SystemClock, so the clock the SDK
        // ages fixes against and the clock a fix is stamped on share an origin before the first fix.
        gate.clock.uptimeBaseMillis = maxOf(gate.clock.uptimeBaseMillis, SystemClock.elapsedRealtime())
        syncSystemClock()

        val unsupported = mutableListOf<Unsupported>()
        val unanswered = mutableListOf<Double>()

        // Queued up front in recorded order, not placed on the timeline: a fixture is stamped when
        // the response arrived, a round trip after the sync that asked for it, so on the timeline it
        // would starve its own fetch. ReplayApiService keeps the stamp as the release time.
        scenario.given
            .filter { it.ev == "fixture.api.fetch" }
            .sortedBy { it.at }
            .forEach { api.enqueue(it) }

        // When Play Services answered, taken from the drive rather than modelled (see ReplayRegistrar).
        registrar.loadAnswerTimes(
            added = scenario.records.filter { it.ev == "registration.added" }.map { it.at },
            removed = scenario.records.filter { it.ev == "registration.removed" }.map { it.at },
            cleared = scenario.records.filter { it.ev == "registration.cleared" }.map { it.at }
        )

        for (record in scenario.stimuli) {
            // Releases every boundary that answered before this input, so the input meets the world
            // it met on the device.
            gate.advanceTo(record.at) { pump() }
            // The gate reaches `record.at` only after its last release; pump again so scheduler work
            // due before this input runs before it.
            pump()
            when (record.ev) {
                // Process lifecycle: module wiring is not what a drive exercises.
                "process.start", "module.init", "module.wake" -> Unit

                // Real external inputs this composition does not model; accepted as no-ops.
                "permission.changed", "os.error" -> Unit

                // Into the real coordinator: replay says when the app came forward, the SDK decides.
                "app.foreground" -> foreground.onForeground()

                // Backgrounding cancels an in-flight fix request, and the harness never holds one:
                // fixes arrive as stimuli.
                "app.background" -> Unit

                "identity.changed" -> {
                    val signedIn = record.boolean("ok") ?: true
                    if (signedIn) {
                        identity.userId = REPLAY_USER_ID
                        // No position: on device the Location module read at identify comes back
                        // empty (`sync.skipped why=no_location`) and the next `prov=bus` fix drives
                        // the sync. Passing a remembered fix would rank from a possibly stale place.
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
                        // Only an arrival is handed over: `prov=bus` is the fix the Location module
                        // delivered. Other sources are reads the SDK made (authored scenarios carry
                        // them for iOS), and feeding one back would invent a location update.
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
                    // On the geofence scope, as `GeofenceBroadcastReceiver` runs it. The pipeline
                    // removes orphan registrations inline, so on the runner's own coroutine it would
                    // park the runner on a boundary only the runner can open.
                    geofenceScope.launch { pipeline.handle(crossing) }
                }

                // The precise fix the polygon controller asked for mid-callback. `triggeringFix`
                // dates it from the recorded age, so the same cached fix the callback carried
                // keeps the same `elapsedRealtimeNanos`.
                "polygon.freshfix.received" ->
                    record.triggeringFix(gate.clock.elapsedRealtime())?.let { fix ->
                        if (!freshFix.deliver(fix)) unanswered.add(record.at)
                    }

                // A fix another app requested, fed through the SDK's passive handler on the geofence
                // scope as `PolygonPassiveReceiver` runs it; this path alone can enter or exit a
                // polygon. A record without a position is a no-op.
                "polygon.passive.received" ->
                    record.triggeringFix(gate.clock.elapsedRealtime())?.let { fix ->
                        geofenceScope.launch { PolygonPassiveReceiver().handleFix(fix) }
                    }

                // The periodic re-check requests its own fix, so the fix it logs is an answer, not a
                // stimulus.
                "polygon.recheck.ran" -> Unit

                else -> unsupported.add(Unsupported(record.ev, record.at))
            }
            // Runs what the stimulus started until it parks at a boundary a later record opens.
            pump()
        }

        // A capture can end with a sync in flight; answer the outstanding boundaries so those
        // decisions are graded rather than lost.
        gate.releaseAll { pump() }
        pump()
        return Result(unsupported, unanswered)
    }

    /**
     * Runs whatever the SDK can now run, on the drive's clock.
     *
     * The gate advances [VirtualClock], but `runCurrent()` drains only what is due on the
     * scheduler's own clock, so the scheduler is advanced by the same amount. Otherwise a `delay()`,
     * such as the movement pass's slot poll in `GeofenceRepository.awaitRefreshSlot`, never resumes.
     */
    private fun pump() {
        // Advanced by how far the drive's clock moved since the last pump, not to an absolute time.
        val now = gate.virtualNow()
        val advanceMillis = ((now - pumpedThrough) * 1000).toLong()
        // `advanceTimeBy` stops short of the instant it lands on; `runCurrent` takes what is due
        // there. `pumpedThrough` moves only by whole milliseconds, so sub-millisecond gaps
        // accumulate instead of each rounding to zero.
        if (advanceMillis > 0) {
            scheduler.advanceTimeBy(advanceMillis)
            pumpedThrough += advanceMillis / 1000.0
        }
        syncSystemClock()
        scheduler.runCurrent()
    }

    /**
     * Robolectric's SystemClock, carried up to the drive's clock before the SDK runs.
     *
     * The SDK ages fixes (`GeofenceLogTail.fixAgeSeconds`) and times polygon-approach sessions
     * against SystemClock, not the injected `Clock`. Left behind, every replayed fix would read hours
     * old and the polygon path would drop it. Only ever moves forward.
     */
    private fun syncSystemClock() {
        val target = gate.clock.elapsedRealtime()
        val now = SystemClock.elapsedRealtime()
        if (target > now) ShadowSystemClock.advanceBy(Duration.ofMillis(target - now))
    }

    /** The identity the host app would have set. Held by the harness, read by the SDK. */
    internal class MutableIdentity {
        var userId: String? = null
    }

    private companion object {
        // Not the recorded user: captures carry no identifier, by design.
        const val REPLAY_USER_ID = "replay-user"

        // `GeofenceLogTail.FixSource` has no member for it: `GeofenceLogger` logs it as a literal.
        const val ARRIVAL_FIX_SOURCE = "bus"
    }
}

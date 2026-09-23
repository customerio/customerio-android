package io.customer.geofence.replay

import android.location.Location
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.geofence.GeofenceLocation
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.GeofenceRegistrar
import io.customer.geofence.api.GeofenceApiEnclosingCircle
import io.customer.geofence.api.GeofenceApiGeometry
import io.customer.geofence.api.GeofenceApiRegion
import io.customer.geofence.api.GeofenceApiResponse
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.location.LocationCoordinates
import io.customer.location.LocationServices
import io.customer.sdk.core.util.ScopeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

/**
 * The doubles a replay substitutes, and nothing else.
 *
 * Each stands exactly where Play Services, the network or the scheduler stands in production. Every
 * SDK decision — ranking, registration policy, containment, cooldown, the anonymous drop, movement
 * routing — runs for real. That claim only holds because the extractions moved those decisions off
 * the far side of these seams.
 */

/**
 * The SDK's coroutine scopes, all sharing one scheduler the replay can pump.
 *
 * [ScopeProviderStub][io.customer.commontest.util.ScopeProviderStub] gives each scope its own
 * scheduler, which is fine when every double answers instantly and nothing ever needs pumping.
 * Once the doubles park, the runner has to be able to say "run whatever is now runnable", and that
 * needs a scheduler it can name. Unconfined is kept deliberately: work still runs inline at the
 * point it becomes runnable, so the only thing that changed is *when* a boundary becomes runnable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ReplayScopeProvider : ScopeProvider {
    val scheduler = TestCoroutineScheduler()

    private fun scope() = TestScope(UnconfinedTestDispatcher(scheduler))

    override val eventBusScope = scope()
    override val lifecycleListenerScope = scope()
    override val inAppLifecycleScope = scope()
    override val locationScope = scope()
    override val geofenceScope = scope()
}

/**
 * The API region the server sent, rebuilt from a folded fixture fence.
 *
 * A fence carrying vertices is a polygon: the ring becomes the GeoJSON geometry and
 * `latitude`/`longitude`/`radius` its enclosing wake circle, which is the pair the SDK's own
 * mapper ([GeofenceApiRegion.toDomain]) needs to register it as a polygon rather than a circle.
 * Without vertices it is the circle those three fields describe, exactly as before.
 */
private fun ScenarioFence.toApiRegion(): GeofenceApiRegion {
    val ring = vertices
    if (ring == null) {
        return GeofenceApiRegion(
            id = id,
            name = name,
            latitude = latitude,
            longitude = longitude,
            radius = radius,
            // Absent means "both", which is what the SDK's own default already is. An empty list
            // would instead register a fence that monitors nothing.
            transitionTypes = transitionTypes.ifEmpty { null },
            geosetIds = geosetIds
        )
    }
    return GeofenceApiRegion(
        id = id,
        name = name,
        shape = "polygon",
        geometry = ring.toGeoJsonPolygon(),
        enclosingCircle = GeofenceApiEnclosingCircle(
            latitude = latitude,
            longitude = longitude,
            baseRadiusMeters = radius
        ),
        transitionTypes = transitionTypes.ifEmpty { null },
        geosetIds = geosetIds
    )
}

/**
 * The ring as a GeoJSON `Polygon`: one outer ring of `[longitude, latitude]` positions.
 *
 * Passed open, in the transform's recorded order — the SDK's mapper re-closes and canonicalises it
 * ([PolygonGeometry.from]), so a closing vertex here would only be collapsed back out.
 */
private fun List<PolygonCoordinate>.toGeoJsonPolygon(): GeofenceApiGeometry {
    val coordinates = buildJsonArray {
        add(
            buildJsonArray {
                this@toGeoJsonPolygon.forEach { vertex ->
                    add(
                        buildJsonArray {
                            add(vertex.longitude)
                            add(vertex.latitude)
                        }
                    )
                }
            }
        )
    }
    return GeofenceApiGeometry(type = "Polygon", coordinates = coordinates)
}

/**
 * Serves the fetch responses the drive recorded, in order and at the moment they arrived.
 *
 * The scenario carries the fences the server actually returned, folded into the `fixture.api.fetch`
 * record by the transform. Replay hands them back rather than inventing a catalogue, so ranking and
 * the registration cap see the real set at the real distances.
 *
 * **The fixture's `at` is a release time, not a position on the timeline.** It is stamped when the
 * response *arrived*, so a fixture always sits one round trip later in the capture than the fetch
 * that asked for it — which is why installing fixtures as timeline records starved the very fetch
 * they answered, and why they are queued up front instead. Under [ReplayBoundaryGate] that same
 * timestamp finally means what it says: the fetch parks until virtual time reaches it. The 749 ms
 * the S24 drive spent waiting for its first response is now 749 ms of virtual time in which other
 * inputs can land, which is exactly what happened on the road.
 */
internal class ReplayApiService(private val gate: ReplayBoundaryGate) : GeofenceApiService {
    private class Fixture(val answeredAt: Double, val result: Result<GeofenceApiResponse>)

    private val queued = ArrayDeque<Fixture>()

    /** Fetches the SDK attempted, whether or not a fixture was waiting. */
    var fetchCount = 0
        private set

    /** Fetches with no fixture left to serve — the replay fetched more often than the drive did. */
    var starvedFetchCount = 0
        private set

    /** Fixtures never consumed — the replay fetched less often than the drive did. */
    val unusedFixtureCount: Int get() = queued.size

    /**
     * Whether the replay asked for exactly the responses the drive recorded.
     *
     * Worth reporting on its own: fixtures are queued up front, so a replay that syncs a different
     * number of times still gets plausible-looking answers and diverges quietly. This is the signal
     * that the divergence is upstream of any decision the matcher grades.
     */
    fun fetchAccounting(): String? = when {
        starvedFetchCount > 0 -> "$fetchCount fetches, $starvedFetchCount unanswered — replay synced more often than the drive"
        unusedFixtureCount > 0 -> "$fetchCount fetches, $unusedFixtureCount fixture(s) unused — replay synced less often than the drive"
        else -> null
    }

    /** Between scenarios: a queue left over from the previous drive answers the next one's fetch. */
    fun reset() {
        queued.clear()
        fetchCount = 0
        starvedFetchCount = 0
    }

    /**
     * Queues one recorded fetch outcome — including the ones that failed.
     *
     * A drive that goes offline records `api.fetch.result ok=false`, and the transform keeps it as
     * a fixture with a null body. Replaying that as an empty *success* tells the SDK the server
     * answered "no fences near you", which unregisters the whole set and turns every later crossing
     * into an orphan. On the 2026-09-09 S24 drive that alone cost 34 of 56 expectations.
     *
     * The failure's cause is not reproduced — only that it failed. What the SDK does with a failed
     * fetch is the same whether DNS or TLS or a timeout caused it.
     */
    fun enqueue(record: ScenarioRecord) {
        val succeeded = record.boolean("ok") ?: true
        if (!succeeded) {
            queued.addLast(
                Fixture(record.at, Result.failure(IllegalStateException("replay: recorded fetch failure")))
            )
            return
        }
        queued.addLast(
            Fixture(
                record.at,
                Result.success(
                    GeofenceApiResponse(
                        config = null,
                        geofences = record.body.map { fence -> fence.toApiRegion() }
                    )
                )
            )
        )
    }

    override suspend fun fetchGeofences(location: GeofenceLocation): Result<GeofenceApiResponse> {
        fetchCount++
        val next = queued.removeFirstOrNull()
        if (next == null) {
            starvedFetchCount++
            // Not an empty success: that would look like "the server says there are no fences
            // nearby" and silently unregister everything the drive had registered. Returned without
            // parking: there is no recorded answer, so there is no recorded moment to wait for.
            return Result.failure(IllegalStateException("replay: no fetch fixture left to serve"))
        }
        gate.awaitVirtual(next.answeredAt, "api.fetch")
        return next.result
    }
}

/**
 * Stands in for Play Services and records what the SDK asked it to monitor.
 *
 * Deliberately always succeeds: a replay grades the SDK's decisions, and an injected GMS failure
 * would be a different scenario, not this one.
 *
 * **It does not succeed instantly.** `addGeofences` is a round trip to a system service, and the
 * gap it leaves is where a crossing for a not-yet-registered fence lands — the `unknown_id` the S24
 * drive recorded and an instant registrar cannot produce. Every call parks for
 * [ReplayBoundaryGate.REGISTRAR_LATENCY_SECONDS] of virtual time, and the registered set changes
 * only once the wait is over, so a read taken during the call sees the world as it was before it.
 */
internal class ReplayRegistrar(private val gate: ReplayBoundaryGate) : GeofenceRegistrar {
    val registeredIds = linkedSetOf<String>()
    val calls = mutableListOf<String>()

    /**
     * When each kind of call answered, taken from the capture.
     *
     * The SDK logs `registration.added` once the OS has accepted the new set and
     * `registration.removed` once it has dropped one, so those timestamps *are* the moments Play
     * Services answered — one `added` per `replaceGeofences` in every recorded drive. Consumed the
     * way fetch fixtures are: in order, the nth call taking the nth recorded answer.
     *
     * This is a recorded duration, not a barrier. Nothing here waits for the SDK to emit anything;
     * a note that moves, or stops being logged, changes when a call returns and never what the
     * matcher grades.
     */
    private val addAnswers = ArrayDeque<Double>()
    private val removeAnswers = ArrayDeque<Double>()

    /**
     * `clearAll`'s own moments, kept apart from [removeAnswers] deliberately.
     *
     * The SDK logs `registration.cleared` for a clear and `registration.removed` for a remove —
     * and it also emits `registration.removed` *inside* a replace, which the replay's own
     * `replaceGeofences` does not reproduce. So the remove pool holds timestamps belonging to
     * calls this double never makes, and letting a clear draw from it hands sign-out a moment that
     * belonged to some earlier replace. Empty until a caller supplies the recorded moments, and
     * the modelled latency is used until then — a fallback is honest where a stolen timestamp is
     * not, because it mis-dates exactly the window that decides `unknown_id` from `already_reported`.
     */
    private val clearAnswers = ArrayDeque<Double>()

    fun loadAnswerTimes(added: List<Double>, removed: List<Double>, cleared: List<Double> = emptyList()) {
        addAnswers.clear(); addAnswers.addAll(added.sorted())
        removeAnswers.clear(); removeAnswers.addAll(removed.sorted())
        clearAnswers.clear(); clearAnswers.addAll(cleared.sorted())
    }

    /** Between scenarios: regions the previous drive registered are not monitored in the next. */
    fun reset() {
        registeredIds.clear()
        calls.clear()
        addAnswers.clear()
        removeAnswers.clear()
        clearAnswers.clear()
    }

    /**
     * The round trip, taken before the change lands rather than after it.
     *
     * The call is recorded up front so a test can see it was made even while it is still in flight,
     * which is also what the phone's log shows.
     */
    private suspend fun roundTrip(call: String, answers: ArrayDeque<Double>) {
        calls.add(call)
        gate.awaitVirtual(nextAnswer(answers), "registrar.$call")
    }

    /**
     * The next recorded answer at or after now, or the modelled fallback when none is left.
     *
     * Answers already behind the clock are discarded rather than used: they belong to a call this
     * replay has already made, and honouring one would return in the past.
     */
    private fun nextAnswer(answers: ArrayDeque<Double>): Double {
        val now = gate.virtualNow()
        while (answers.isNotEmpty() && answers.first() < now) answers.removeFirst()
        return answers.removeFirstOrNull()
            ?: (now + ReplayBoundaryGate.REGISTRAR_LATENCY_SECONDS)
    }

    override suspend fun replaceGeofences(
        regions: List<GeofenceRegion>,
        existingBusinessIds: Set<String>
    ): Result<Unit> {
        // Production returns here without touching Play Services: `replaceGeofencesInternal`
        // disables the receiver and returns success for an empty region list. Round-tripping it
        // anyway spent a recorded answer on a call the OS never saw, which shifts every later
        // release, and cleared `registeredIds` where production leaves the registrations alone.
        if (regions.isEmpty()) return Result.success(Unit)
        roundTrip("replace(${regions.size})", addAnswers)
        // Mirrors the OS: what is monitored afterwards is the requested set, kept ids included.
        registeredIds.clear()
        registeredIds.addAll(regions.map { it.id })
        return Result.success(Unit)
    }

    override suspend fun replaceGeofencesForBootRestore(regions: List<GeofenceRegion>): Result<Unit> =
        replaceGeofences(regions, emptySet())

    override suspend fun removeGeofencesByIds(ids: List<String>): Result<Unit> {
        // Same early return as production, for the same reason.
        if (ids.isEmpty()) return Result.success(Unit)
        roundTrip("remove(${ids.joinToString(",")})", removeAnswers)
        registeredIds.removeAll(ids.toSet())
        return Result.success(Unit)
    }

    override suspend fun replaceMovementTrigger(region: GeofenceRegion): Result<Unit> {
        // An upsert at the OS, not a replace. Production goes straight to `registerBatch`, which
        // adds this one circle and leaves every business registration monitored — delegating to
        // `replaceGeofences` cleared them all. It also emits no `registration.added`
        // (`logGeofencesRegistered` fires only from `replaceGeofencesInternal`), so this must not
        // spend a recorded add answer: doing so mis-dates the call and shifts every later one.
        roundTrip("replaceMovementTrigger", ArrayDeque())
        registeredIds.add(region.id)
        return Result.success(Unit)
    }

    override suspend fun clearAll(): Result<Unit> {
        roundTrip("clearAll", clearAnswers)
        registeredIds.clear()
        return Result.success(Unit)
    }
}

/**
 * Stands in for the location module's OS-facing half.
 *
 * The only outward call the foreground path makes is asking for a fix. Production hands that to
 * `FusedLocationProviderClient` and the answer arrives later on the event bus; in a replay the
 * answer is already on the timeline as the next `location.fix` stimulus, so this records the
 * request and returns.
 *
 * [getLastKnownLocation] stays empty, and that is the faithful answer rather than a gap.
 * `setLastKnownLocation` is a *host-app* API — "sets the last known location from the host app's
 * existing location system" — and nothing inside the SDK calls it. No recorded drive called it
 * either, so `resolveAnchor` fell through to its `registrationCenter` branch on the phone exactly
 * as it does here. Feeding it from replayed fixes would hand the replay an anchor production never
 * had, which is the bug the runner's `identity.changed` comment already describes.
 */
internal class ReplayLocationServices : LocationServices {
    /** Fixes the SDK asked for. A drive's foreground entries should each produce one. */
    var silentRequestCount = 0
        private set

    var lastKnown: LocationCoordinates? = null

    fun reset() {
        silentRequestCount = 0
        lastKnown = null
    }

    @OptIn(InternalCustomerIOApi::class)
    override fun requestLocationUpdateSilently() {
        silentRequestCount++
    }

    override fun getLastKnownLocation(): LocationCoordinates? = lastKnown

    override fun setLastKnownLocation(latitude: Double, longitude: Double) {
        lastKnown = LocationCoordinates(latitude = latitude, longitude = longitude)
    }

    override fun setLastKnownLocation(location: Location) {
        setLastKnownLocation(location.latitude, location.longitude)
    }

    // Host-facing entry point with analytics side effects; geofencing never calls it.
    override fun requestLocationUpdate() = Unit
}

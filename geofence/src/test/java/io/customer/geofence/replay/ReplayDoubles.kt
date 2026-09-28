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
 * The SDK's coroutine scopes on one shared scheduler, so the runner can run whatever a boundary
 * release made runnable ([ScopeProviderStub][io.customer.commontest.util.ScopeProviderStub] gives
 * each scope its own). Unconfined, so work still runs inline once it becomes runnable.
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
 * With vertices it is a polygon: the ring becomes the GeoJSON geometry and
 * `latitude`/`longitude`/`radius` its enclosing wake circle, as the SDK's mapper expects. Without
 * vertices it is the circle those three fields describe.
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
 * The ring as a GeoJSON `Polygon`: one outer ring of `[longitude, latitude]` positions, left open
 * because the SDK's mapper canonicalises the ring and would drop a closing vertex anyway.
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
 * Serves the fetch responses the drive recorded, in order, each at the moment it arrived, so
 * ranking and the registration cap see the real fence set.
 *
 * A fixture's `at` is a release time, not a timeline position: it is stamped when the response
 * arrived, one round trip after the fetch that asked for it. So fixtures are queued up front and
 * each fetch parks on [ReplayBoundaryGate] until virtual time reaches its `at`.
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
     * Null when the replay fetched exactly as often as the drive. Fixtures are queued up front, so a
     * replay that syncs a different number of times still gets plausible answers and diverges quietly.
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
     * Queues one recorded fetch outcome, failures included.
     *
     * A recorded `ok=false` becomes a failure, not an empty success: an empty success tells the SDK
     * there are no fences nearby, which unregisters the whole set. The cause is not reproduced; the
     * SDK handles every failed fetch the same way.
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
            // Not an empty success, which would unregister everything. Not parked: with no recorded
            // answer there is no recorded moment to wait for.
            return Result.failure(IllegalStateException("replay: no fetch fixture left to serve"))
        }
        gate.awaitVirtual(next.answeredAt, "api.fetch")
        return next.result
    }
}

/**
 * Stands in for Play Services and records what the SDK asked it to monitor.
 *
 * Always succeeds: an injected GMS failure would be a different scenario. Each call parks until the
 * capture's recorded answer (or [ReplayBoundaryGate.REGISTRAR_LATENCY_SECONDS] when none is left)
 * and the registered set changes only after, so a crossing landing mid-call sees the old set.
 */
internal class ReplayRegistrar(private val gate: ReplayBoundaryGate) : GeofenceRegistrar {
    val registeredIds = linkedSetOf<String>()
    val calls = mutableListOf<String>()

    /**
     * When each kind of call answered, from the capture: the SDK logs `registration.added` and
     * `registration.removed` once the OS has answered. Consumed in order, the nth call taking the
     * nth recorded answer. These only set when a call returns; nothing waits for the SDK to log.
     */
    private val addAnswers = ArrayDeque<Double>()
    private val removeAnswers = ArrayDeque<Double>()

    /**
     * `clearAll`'s moments (`registration.cleared`), kept apart from [removeAnswers]: production
     * also logs `registration.removed` inside a replace, which this double's replace does not
     * reproduce, so a clear drawing from that pool could take an earlier replace's timestamp.
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

    /** Records the call before parking, so a test sees it while it is still in flight. */
    private suspend fun roundTrip(call: String, answers: ArrayDeque<Double>) {
        calls.add(call)
        gate.awaitVirtual(nextAnswer(answers), "registrar.$call")
    }

    /**
     * The next recorded answer at or after now, or the modelled fallback when none is left.
     *
     * Answers already behind the clock are discarded: they belong to an earlier call (or one this
     * double does not round-trip), and honouring one would return in the past.
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
        // Production returns without touching Play Services for an empty list and leaves the
        // existing registrations in place.
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
        // Same early return as production.
        if (ids.isEmpty()) return Result.success(Unit)
        roundTrip("remove(${ids.joinToString(",")})", removeAnswers)
        registeredIds.removeAll(ids.toSet())
        return Result.success(Unit)
    }

    override suspend fun replaceMovementTrigger(region: GeofenceRegion): Result<Unit> {
        // An upsert, not a replace: production adds this one circle via `registerBatch` and leaves
        // business registrations monitored. It logs no `registration.added`, so it must not consume
        // a recorded add answer.
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
 * A requested fix arrives in the replay as a later `location.fix` stimulus, so a request is only
 * counted. [getLastKnownLocation] stays empty unless set: `setLastKnownLocation` is a host-app API
 * the SDK never calls, so feeding it replayed fixes would give the SDK an anchor production lacks.
 */
internal class ReplayLocationServices : LocationServices {
    /** Silent fix requests the SDK made. */
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

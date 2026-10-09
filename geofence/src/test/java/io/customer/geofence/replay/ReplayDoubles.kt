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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

/**
 * All SDK scopes on one scheduler (unlike
 * [ScopeProviderStub][io.customer.commontest.util.ScopeProviderStub]), so the runner can run what a
 * boundary release made runnable. Unconfined, so that work runs inline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ReplayScopeProvider(val scheduler: TestCoroutineScheduler = TestCoroutineScheduler()) : ScopeProvider {

    private fun scope() = TestScope(UnconfinedTestDispatcher(scheduler))

    override val eventBusScope = scope()
    override val lifecycleListenerScope = scope()
    override val inAppLifecycleScope = scope()
    override val locationScope = scope()
    override val geofenceScope = scope()

    fun stop() {
        eventBusScope.cancel()
        lifecycleListenerScope.cancel()
        inAppLifecycleScope.cancel()
        locationScope.cancel()
        geofenceScope.cancel()
    }
}

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
            geosetIds = geosetIds,
            dwellThresholdSeconds = dwellThresholdSeconds
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
        geosetIds = geosetIds,
        dwellThresholdSeconds = dwellThresholdSeconds
    )
}

/** Left open: the SDK's mapper canonicalises the ring and would drop a closing vertex anyway. */
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
 * Serves the drive's recorded fetch responses in order. A fixture's `at` is when the response
 * arrived, a round trip after its fetch, so each fetch parks on [ReplayBoundaryGate] until then.
 */
internal class ReplayApiService(private val gate: ReplayBoundaryGate) : GeofenceApiService {
    private class Fixture(val answeredAt: Double, val result: Result<GeofenceApiResponse>)

    private val queued = ArrayDeque<Fixture>()

    var fetchCount = 0
        private set

    /** Fetches that found no fixture left to serve. */
    var starvedFetchCount = 0
        private set

    val unusedFixtureCount: Int get() = queued.size

    /**
     * Null when the replay fetched exactly as often as the drive. Otherwise it diverges quietly,
     * since queued fixtures still give plausible answers.
     */
    fun fetchAccounting(): String? = when {
        starvedFetchCount > 0 -> "$fetchCount fetches, $starvedFetchCount unanswered — replay synced more often than the drive"
        unusedFixtureCount > 0 -> "$fetchCount fetches, $unusedFixtureCount fixture(s) unused — replay synced less often than the drive"
        else -> null
    }

    fun reset() {
        queued.clear()
        fetchCount = 0
        starvedFetchCount = 0
    }

    /**
     * A recorded `ok=false` becomes a failure, not an empty success, which would unregister every
     * fence. The cause is not reproduced; the SDK handles every failed fetch the same way.
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
            // A failure, as in `enqueue`. Not parked: there is no recorded moment to wait for.
            return Result.failure(IllegalStateException("replay: no fetch fixture left to serve"))
        }
        gate.awaitVirtual(next.answeredAt, "api.fetch")
        return next.result
    }
}

/**
 * Play Services stand-in that always succeeds. Each call parks until the capture's recorded answer
 * and changes [registeredIds] only after, so a crossing landing mid-call sees the old set.
 */
internal class ReplayRegistrar(private val gate: ReplayBoundaryGate) : GeofenceRegistrar {
    val registeredIds = linkedSetOf<String>()
    val calls = mutableListOf<String>()

    /**
     * Recorded answer times, consumed in order: the SDK logs `registration.added`/`.removed` once
     * the OS answers.
     */
    private val addAnswers = ArrayDeque<Double>()
    private val removeAnswers = ArrayDeque<Double>()

    /**
     * `registration.cleared` times, apart from [removeAnswers]: production also logs
     * `registration.removed` inside a replace, so a clear drawing from that pool could take an
     * earlier replace's time.
     */
    private val clearAnswers = ArrayDeque<Double>()

    fun loadAnswerTimes(added: List<Double>, removed: List<Double>, cleared: List<Double> = emptyList()) {
        addAnswers.clear(); addAnswers.addAll(added.sorted())
        removeAnswers.clear(); removeAnswers.addAll(removed.sorted())
        clearAnswers.clear(); clearAnswers.addAll(cleared.sorted())
    }

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
     * Answers behind the clock are dropped: they belong to an earlier call (or one this double does
     * not round-trip), and honouring one would return in the past.
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
        // Same as production: an empty list leaves existing registrations in place.
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
        if (ids.isEmpty()) return Result.success(Unit)
        roundTrip("remove(${ids.joinToString(",")})", removeAnswers)
        registeredIds.removeAll(ids.toSet())
        return Result.success(Unit)
    }

    override suspend fun replaceMovementTrigger(region: GeofenceRegion): Result<Unit> {
        // An upsert that leaves business registrations monitored. Production logs no
        // `registration.added` for it, so it must not consume a recorded add answer.
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
 * A requested fix arrives later as a `location.fix` stimulus, so requests are only counted. Replayed
 * fixes never set [lastKnown]: only the host app calls `setLastKnownLocation`, so it would give the
 * SDK an anchor production lacks.
 */
internal class ReplayLocationServices : LocationServices {
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

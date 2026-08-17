package io.customer.geofence

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.api.GeofenceApiResponse
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.geofence.polygon.PolygonSupport
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The live path end to end, with the real mapper and the real distance filter, run twice: once with
 * the opt-in absent and once with the production opt-in the graph actually wires.
 *
 * Without it, a polygon the backend sends must not reach OS registration or produce a business
 * transition — every seam defaults to [io.customer.geofence.polygon.PolygonSupport.Disabled], so a
 * path that forgets the wiring fails closed. With it, the *same* response registers the polygon,
 * which is what proves the drops below are the opt-in and not a missing capability.
 *
 * The repository's other behaviour is covered by [GeofenceRepositoryTest], which mocks the distance
 * filter. This class deliberately uses the real one, because mapping and ranking are the two gates a
 * polygon has to pass to become a registered fence.
 */
@RunWith(RobolectricTestRunner::class)
class GeofencePolygonLivePathTest : RobolectricTest() {

    private val apiService: GeofenceApiService = mockk(relaxed = true)
    private val store: GeofenceRegionStore = mockk(relaxed = true)
    private val manager: GeofenceManager = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val cooldownFilter: GeofenceCooldownFilter = mockk(relaxed = true)
    private val transitionEmitter: GeofenceTransitionEmitter = mockk(relaxed = true)
    private val clock: Clock = mockk(relaxed = true)
    private val packageInfo: GeofencePackageInfo = mockk {
        every { lastUpdateTimeMs() } returns null
    }

    // Shared by the repository, the mapper and the distance filter (both resolve it from the graph),
    // so one mock sees every drop reason on the path.
    private val mockLogger: GeofenceLogger = mockk(relaxed = true)
    private val jsonSerializer = GeofenceJsonSerializer()

    private lateinit var repository: GeofenceRepositoryImpl

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph { sdk { overrideDependency<GeofenceLogger>(mockLogger) } }
            }
        )
        every { clock.currentTimeMillis() } answers { System.currentTimeMillis() }
        every { secureUserStore.getUserId() } returns "user-42"
        every { store.getLastSyncTimestamp() } returns null // never synced -> remote fetch
        every { store.getRegisteredIds() } returns emptySet()
        every { store.getCachedRegions() } returns emptyList()
        coEvery { manager.replaceGeofences(any(), any()) } returns Result.success(Unit)
        // No opt-in supplied at any seam: every default is Disabled.
        repository = buildRepository(PolygonSupport.Disabled)
    }

    private fun buildRepository(polygonSupport: PolygonSupport) = GeofenceRepositoryImpl(
        apiService = apiService,
        store = store,
        distanceFilter = GeofenceDistanceFilter(polygonSupport = polygonSupport),
        manager = manager,
        secureUserStore = secureUserStore,
        cooldownFilter = cooldownFilter,
        transitionEmitter = transitionEmitter,
        clock = clock,
        packageInfo = packageInfo,
        logger = mockLogger,
        polygonSupport = polygonSupport
    )

    @Test
    fun refresh_givenPolygonAndCircleResponse_expectOnlyCircleAndTriggerRegistered() = runTest {
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(response(POLYGON_AND_CIRCLE))
        val registered = slot<List<GeofenceRegion>>()

        val result = repository.refresh(latitude = 37.775, longitude = -122.419)

        result.isSuccess shouldBeEqualTo true
        coVerify { manager.replaceGeofences(capture(registered), any()) }
        registered.captured.map { it.id } shouldBeEqualTo
            listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "circle")
        registered.captured.any { it.isPolygon } shouldBeEqualTo false
        verify { mockLogger.logPolygonDroppedUnsupportedRuntime("campus") }
    }

    @Test
    fun refresh_givenDeviceInsidePolygon_expectNoBusinessTransitionEmitted() = runTest {
        // Everything initial-enter synthesis needs is in place — the device is inside the ring and
        // inside the enclosing circle, and the store reports the fence as contained — except a
        // registered polygon. Nothing may be emitted for it.
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(response(POLYGON_AND_CIRCLE))
        every { store.getEnteredIds() } returns setOf("campus", "circle")

        repository.refresh(latitude = 37.7750, longitude = -122.4194)

        coVerify(exactly = 0) {
            transitionEmitter.emit(eq("campus"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun refresh_givenPolygonOnlyResponse_expectRefreshFailsAndLiveStateUntouched() = runTest {
        // Nothing usable came back. The refresh fails rather than "succeeding" with an empty set, so
        // whatever is already registered with the OS stays registered and cached.
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(response(POLYGON_ONLY))

        val result = repository.refresh(latitude = 37.775, longitude = -122.419)

        result.isFailure shouldBeEqualTo true
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
        verify(exactly = 0) { store.saveCachedRegions(any()) }
        verify(exactly = 0) { store.saveRegisteredIds(any()) }
    }

    @Test
    fun refresh_givenPolygonAlreadyInCache_expectNeverRegisteredAsItsEnclosingCircle() = runTest {
        // Defence in depth for a catalog that already holds a polygon (written by a build that could
        // monitor it, then downgraded): ranking drops it rather than registering its trigger circle,
        // and the rest of the pass still runs.
        val polygon = GeofenceRegion(
            id = "campus",
            latitude = 37.7750,
            longitude = -122.4194,
            radius = 1_200f,
            polygonVertices = campusRing()
        )
        // Time-fresh cache at the current location, nothing registered yet: a local re-rank, so the
        // cached polygon is the only candidate the filter sees.
        every { store.getLastSyncTimestamp() } returns System.currentTimeMillis() - 60_000L
        every { store.getLastApiFetchLocation() } returns GeofenceLocation(37.7750, -122.4194)
        every { store.getLastMovementTriggerLocation() } returns GeofenceLocation(37.7750, -122.4194)
        every { store.getCachedRegions() } returns listOf(polygon)
        every { store.getCachedConfig() } returns null
        val registered = slot<List<GeofenceRegion>>()

        repository.refresh(latitude = 37.7750, longitude = -122.4194)

        coVerify { manager.replaceGeofences(capture(registered), any()) }
        registered.captured.map { it.id } shouldBeEqualTo listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID)
        verify { mockLogger.logPolygonRegionNotRanked(eq("campus"), any()) }
        coVerify(exactly = 0) {
            transitionEmitter.emit(eq("campus"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ---------- the same live path with the production opt-in the graph wires ----------

    @Test
    fun refresh_givenProductionOptInAndPolygonResponse_expectPolygonRegisteredAlongsideCircle() = runTest {
        // Identical response and identical mapper/ranker; only the opt-in differs. The polygon reaches
        // the OS as a registered fence, carrying its ring rather than being flattened to a circle.
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(response(POLYGON_AND_CIRCLE))
        val registered = slot<List<GeofenceRegion>>()

        val result = buildRepository(PolygonSupport.Enabled)
            .refresh(latitude = 37.775, longitude = -122.419)

        result.isSuccess shouldBeEqualTo true
        coVerify { manager.replaceGeofences(capture(registered), any()) }
        registered.captured.map { it.id } shouldBeEqualTo
            listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "campus", "circle")
        registered.captured.single { it.id == "campus" }.polygonVertices.shouldNotBeNull()
        verify(exactly = 0) { mockLogger.logPolygonDroppedUnsupportedRuntime(any()) }
        verify(exactly = 0) { mockLogger.logPolygonRegionNotRanked(any(), any()) }
    }

    @Test
    fun refresh_givenProductionOptInAndPolygonOnlyResponse_expectRefreshSucceeds() = runTest {
        // The "all regions dropped" failure in the disabled case is the opt-in talking, not a
        // malformed response: with the runtime present the same payload is perfectly usable.
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(response(POLYGON_ONLY))

        val result = buildRepository(PolygonSupport.Enabled)
            .refresh(latitude = 37.775, longitude = -122.419)

        result.isSuccess shouldBeEqualTo true
        coVerify { manager.replaceGeofences(any(), any()) }
    }

    private fun campusRing() = listOf(
        PolygonCoordinate(37.7745, -122.4200),
        PolygonCoordinate(37.7745, -122.4188),
        PolygonCoordinate(37.7755, -122.4188),
        PolygonCoordinate(37.7755, -122.4200)
    )

    private fun response(raw: String): GeofenceApiResponse =
        jsonSerializer.decode(GeofenceApiResponse.serializer(), raw, lenient = true)

    private companion object {
        const val CAMPUS_GEOMETRY = """
            {
              "type": "Polygon",
              "coordinates": [[
                [-122.4200, 37.7745],
                [-122.4188, 37.7745],
                [-122.4188, 37.7755],
                [-122.4200, 37.7755],
                [-122.4200, 37.7745]
              ]]
            }
        """

        val POLYGON_AND_CIRCLE = """
            {
              "config": { "android": { "max_business_geofence": 19 } },
              "geofences": [
                { "id": "campus", "geometry": $CAMPUS_GEOMETRY },
                { "id": "circle", "latitude": 37.775, "longitude": -122.419, "radius": 100 }
              ]
            }
        """.trimIndent()

        val POLYGON_ONLY = """
            {
              "config": { "android": { "max_business_geofence": 19 } },
              "geofences": [
                { "id": "campus", "geometry": $CAMPUS_GEOMETRY }
              ]
            }
        """.trimIndent()
    }
}

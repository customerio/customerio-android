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
 * The live path with the real mapper and distance filter, the two gates a polygon must pass to be
 * registered, run without and with the polygon opt-in.
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

    // The mapper and filter resolve this from the graph, so one mock sees every drop reason.
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
        bootSessionProvider = { "boot" },
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
        // Everything initial-enter synthesis needs is in place except a registered polygon.
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(response(POLYGON_AND_CIRCLE))
        every { store.getEnteredIds() } returns setOf("campus", "circle")

        repository.refresh(latitude = 37.7750, longitude = -122.4194)

        coVerify(exactly = 0) {
            transitionEmitter.emit(eq("campus"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun refresh_givenPolygonOnlyResponse_expectRefreshFailsAndLiveStateUntouched() = runTest {
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(response(POLYGON_ONLY))

        val result = repository.refresh(latitude = 37.775, longitude = -122.419)

        result.isFailure shouldBeEqualTo true
        coVerify(exactly = 0) { manager.replaceGeofences(any(), any()) }
        verify(exactly = 0) { store.saveCachedRegions(any()) }
        verify(exactly = 0) { store.saveRegisteredIds(any()) }
    }

    @Test
    fun refresh_givenPolygonAlreadyInCache_expectNeverRegisteredAsItsEnclosingCircle() = runTest {
        // A catalog written by a build that could monitor polygons, then downgraded.
        val polygon = GeofenceRegion(
            id = "campus",
            latitude = 37.7750,
            longitude = -122.4194,
            radius = 1_200f,
            polygonVertices = campusRing()
        )
        // Fresh cache here and nothing registered: a local re-rank over the cached polygon only.
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

    @Test
    fun refresh_givenRegisteredPolygonContainingTheFix_expectPinnedPastTheCap() = runTest {
        // Inside the registered wake circle, so an EXIT is owed; the circle ranks nearer for the
        // one slot.
        val enabledRepository = buildRepository(PolygonSupport.Enabled)
        every { store.getRegisteredIds() } returns setOf("campus")
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(response(POLYGON_AND_CIRCLE_CAP_ONE))
        val registered = slot<List<GeofenceRegion>>()

        enabledRepository.refresh(latitude = 37.7750, longitude = -122.4185)

        coVerify { manager.replaceGeofences(capture(registered), any()) }
        registered.captured.map { it.id } shouldBeEqualTo
            listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "campus")
    }

    @Test
    fun refresh_givenUnregisteredPolygonContainingTheFix_expectTheCapStillApplies() = runTest {
        // Never registered, so it owes no EXIT and competes for the cap like any other candidate.
        val enabledRepository = buildRepository(PolygonSupport.Enabled)
        every { store.getRegisteredIds() } returns emptySet()
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(response(POLYGON_AND_CIRCLE_CAP_ONE))
        val registered = slot<List<GeofenceRegion>>()

        enabledRepository.refresh(latitude = 37.7750, longitude = -122.4185)

        coVerify { manager.replaceGeofences(capture(registered), any()) }
        registered.captured.map { it.id } shouldBeEqualTo
            listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "circle")
    }

    private fun response(raw: String): GeofenceApiResponse =
        jsonSerializer.decode(GeofenceApiResponse.serializer(), raw, lenient = true)

    private companion object {
        const val CAMPUS_WAKE_CIRCLE = """
            {
              "latitude": 37.775,
              "longitude": -122.4194,
              "base_radius_m": 100
            }
        """

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
                {
                  "id": "campus",
                  "shape": "polygon",
                  "geometry": $CAMPUS_GEOMETRY,
                  "enclosing_circle": $CAMPUS_WAKE_CIRCLE
                },
                { "id": "circle", "latitude": 37.775, "longitude": -122.419, "radius": 100 }
              ]
            }
        """.trimIndent()

        val POLYGON_AND_CIRCLE_CAP_ONE = """
            {
              "config": { "android": { "max_business_geofence": 1 } },
              "geofences": [
                {
                  "id": "campus",
                  "shape": "polygon",
                  "geometry": $CAMPUS_GEOMETRY,
                  "enclosing_circle": $CAMPUS_WAKE_CIRCLE
                },
                { "id": "circle", "latitude": 37.775, "longitude": -122.419, "radius": 100 }
              ]
            }
        """.trimIndent()

        val POLYGON_ONLY = """
            {
              "config": { "android": { "max_business_geofence": 19 } },
              "geofences": [
                {
                  "id": "campus",
                  "shape": "polygon",
                  "geometry": $CAMPUS_GEOMETRY,
                  "enclosing_circle": $CAMPUS_WAKE_CIRCLE
                }
              ]
            }
        """.trimIndent()
    }
}

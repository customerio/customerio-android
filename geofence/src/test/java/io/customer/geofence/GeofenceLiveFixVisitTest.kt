package io.customer.geofence

import android.location.Location
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.api.GeofenceApiRegion
import io.customer.geofence.api.GeofenceApiResponse
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.polygon.PolygonGeofenceServiceController
import io.customer.geofence.store.GeofenceRegionStoreImpl
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.PendingDeliveryStore
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A host refresh's live fix that is fresh and clears a circle's edge by more than its accuracy
 * proves the device outside the circle that pass registers, so the next native ENTER is the
 * crossing and the visit's DWELL reports when it began. Real services, repository, store,
 * pipeline, dwell coordinator, processor, emitter and outbox; only Play services, the API, the
 * polygon controller, the permission checker and package info are faked.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class GeofenceLiveFixVisitTest : RobolectricTest() {

    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val apiService: GeofenceApiService = mockk()
    private val registrar: GeofenceRegistrar = mockk(relaxed = true)
    private val polygonController: PolygonGeofenceServiceController = mockk(relaxed = true)
    private val permissionChecker: GeofencePermissionChecker = mockk()
    private val packageInfo: GeofencePackageInfo = mockk()
    private val cooldownFilter: GeofenceCooldownFilter = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private val clock: Clock = mockk(relaxed = true)

    /** Both clocks derive from this, so wall stamps always agree with the boot clock. */
    private var nowElapsedMs = START_ELAPSED_MS

    private lateinit var store: GeofenceRegionStoreImpl
    private lateinit var outbox: PendingDeliveryStore<PendingGeofenceDelivery>

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { argument(ApplicationArgument(applicationMock)) })
        store = GeofenceRegionStoreImpl(
            context = applicationMock,
            jsonSerializer = GeofenceJsonSerializer(),
            logger = mockk(relaxed = true)
        ).also { it.clearAll() }
        outbox = PendingDeliveryStore(
            context = applicationMock,
            fileName = "cio_test_geofence_live_fix_visit_outbox.json",
            elementSerializer = PendingGeofenceDelivery.serializer(),
            logger = mockk(relaxed = true)
        ).also { it.removeAll() }

        every { secureUserStore.getUserId() } returns USER_ID
        every { clock.elapsedRealtime() } answers { nowElapsedMs }
        every { clock.currentTimeMillis() } answers { WALL_CLOCK_AT_BOOT_MS + nowElapsedMs }
        every { clock.currentTimeSeconds() } answers { wallSeconds() }
        every { cooldownFilter.suppressedForSeconds(any(), any(), any()) } returns null
        every { permissionChecker.hasRequiredLocationPermissions() } returns true
        every { permissionChecker.isBackgroundDeliveryAvailable() } returns true
        // No stamp to compare against, so no app update reads as an OS wipe.
        every { packageInfo.lastUpdateTimeMs() } returns null
        // Runs the publish whether or not the repository routes it through the controller.
        every { polygonController.publishRegistrationIfCurrent(any(), any(), any()) } answers {
            thirdArg<() -> Unit>().invoke()
            true
        }
        coEvery { apiService.fetchGeofences(any()) } returns Result.failure(IOException("offline"))
        coEvery { registrar.removeGeofencesByIds(any()) } returns Result.success(Unit)
        coEvery { registrar.replaceGeofences(any(), any()) } returns Result.success(Unit)

        store.beginUserSession(USER_ID)
        // Cached by an earlier fetch and never registered, so this pass is the circle's first.
        store.saveCachedRegions(listOf(circle()))
    }

    @Test
    fun liveFix_offlineFallback_givenAccurateFixClearOfTheEdge_expectDwellWithEntryTiming() = runTest {
        val wiring = wiring()

        wiring.refreshFromHostFix(fixQuality(accuracyMeters = ACCURATE_METERS))
        val enteredAtSeconds = wiring.enterThenDwell()

        coVerify(exactly = 1) { apiService.fetchGeofences(any()) }
        val dwell = dwell()
        dwell.enteredAt shouldBeEqualTo enteredAtSeconds
        dwell.dwellDurationSeconds shouldBeEqualTo DWELL_RECEIPT_GAP_SECONDS
    }

    @Test
    fun liveFix_localRerank_givenAccurateFixClearOfTheEdge_expectDwellWithEntryTiming() = runTest {
        // Fetched from here moments ago, so the pass re-ranks the cache without calling the API.
        store.saveApiFetchStateIfCurrent(
            location = GeofenceLocation(FIX_LATITUDE, CIRCLE_LONGITUDE),
            syncTimestamp = WALL_CLOCK_AT_BOOT_MS + nowElapsedMs,
            expectedUserStateGeneration = store.userStateGeneration()
        ) shouldBeEqualTo true
        val wiring = wiring()

        wiring.refreshFromHostFix(fixQuality(accuracyMeters = ACCURATE_METERS))
        val enteredAtSeconds = wiring.enterThenDwell()

        coVerify(exactly = 0) { apiService.fetchGeofences(any()) }
        val dwell = dwell()
        dwell.enteredAt shouldBeEqualTo enteredAtSeconds
        dwell.dwellDurationSeconds shouldBeEqualTo DWELL_RECEIPT_GAP_SECONDS
    }

    @Test
    fun liveFix_remoteFetch_givenAccurateFixClearOfTheEdge_expectDwellWithEntryTiming() = runTest {
        coEvery { apiService.fetchGeofences(any()) } returns Result.success(
            GeofenceApiResponse(
                geofences = listOf(
                    GeofenceApiRegion(
                        id = CIRCLE_ID,
                        latitude = CIRCLE_LATITUDE,
                        longitude = CIRCLE_LONGITUDE,
                        radius = 100.0,
                        dwellThresholdSeconds = 60L
                    )
                )
            )
        )
        val wiring = wiring()

        wiring.refreshFromHostFix(fixQuality(accuracyMeters = ACCURATE_METERS))
        val enteredAtSeconds = wiring.enterThenDwell()

        coVerify(exactly = 1) { apiService.fetchGeofences(any()) }
        dwell().enteredAt shouldBeEqualTo enteredAtSeconds
        dwell().dwellDurationSeconds shouldBeEqualTo DWELL_RECEIPT_GAP_SECONDS
    }

    @Test
    fun liveFix_freshCache_givenCircleAlreadyWatched_expectProofDatedToTheFixWithoutRegisteringAgain() = runTest {
        val wiring = wiring()
        wiring.refreshFromHostFix(GeofenceFixQuality.UNKNOWN)
        store.getRegistrationIncarnation(CIRCLE_ID)?.outsideProvenAtElapsedMs.shouldBeNull()
        store.saveApiFetchStateIfCurrent(
            location = GeofenceLocation(FIX_LATITUDE, CIRCLE_LONGITUDE),
            syncTimestamp = WALL_CLOCK_AT_BOOT_MS + nowElapsedMs,
            expectedUserStateGeneration = store.userStateGeneration()
        ) shouldBeEqualTo true
        nowElapsedMs += 10_000L
        val quality = fixQuality(accuracyMeters = ACCURATE_METERS)

        wiring.refreshFromHostFix(quality)

        store.getRegistrationIncarnation(CIRCLE_ID)?.outsideProvenAtElapsedMs shouldBeEqualTo
            quality.fixElapsedRealtimeMillis
        coVerify(exactly = 1) { registrar.replaceGeofences(any(), any()) }
        coVerify(exactly = 1) { apiService.fetchGeofences(any()) }
        val enteredAtSeconds = wiring.enterThenDwell()
        dwell().enteredAt shouldBeEqualTo enteredAtSeconds
        dwell().dwellDurationSeconds shouldBeEqualTo DWELL_RECEIPT_GAP_SECONDS
    }

    @Test
    fun liveFix_givenNoReportedAccuracy_expectDwellWithoutEntryTiming() = runTest {
        val wiring = wiring()

        wiring.refreshFromHostFix(fixQuality(accuracyMeters = null))
        wiring.enterThenDwell()

        dwell().assertNoEntryTiming()
    }

    @Test
    fun liveFix_givenNoSampleTime_expectDwellWithoutEntryTiming() = runTest {
        val wiring = wiring()

        wiring.refreshFromHostFix(GeofenceFixQuality(horizontalAccuracyMeters = ACCURATE_METERS))
        wiring.enterThenDwell()

        dwell().assertNoEntryTiming()
    }

    @Test
    fun liveFix_givenAccuracyTooCoarseToClearTheEdge_expectDwellWithoutEntryTiming() = runTest {
        val wiring = wiring()

        // ~500 m beyond the edge, but 500 m of error plus the 20 m margin reaches inside.
        wiring.refreshFromHostFix(fixQuality(accuracyMeters = 500f))
        wiring.enterThenDwell()

        dwell().assertNoEntryTiming()
    }

    @Test
    fun liveFix_givenFetchAgingTheFixPastTheProofWindow_expectDwellWithoutEntryTiming() = runTest {
        // Fresh on arrival, but registration follows the failed fetch, 151 s after the fix.
        coEvery { apiService.fetchGeofences(any()) } coAnswers {
            nowElapsedMs += 150_000L
            Result.failure(IOException("offline"))
        }
        val wiring = wiring()

        wiring.refreshFromHostFix(fixQuality(accuracyMeters = ACCURATE_METERS))
        wiring.enterThenDwell()

        dwell().assertNoEntryTiming()
    }

    private fun TestScope.wiring(): Wiring {
        val emitter = GeofenceTransitionEmitter(cooldownFilter, outbox, mockk(relaxed = true), store, logger)
        val processor = GeofenceBusinessTransitionProcessor(store, secureUserStore, emitter, logger)
        val coordinator = GeofenceDwellCoordinator(store, processor, clock) { BOOT_SESSION_ID }
        val repository = GeofenceRepositoryImpl(
            apiService = apiService,
            store = store,
            distanceFilter = GeofenceDistanceFilter(logger = logger),
            manager = registrar,
            secureUserStore = secureUserStore,
            cooldownFilter = cooldownFilter,
            transitionEmitter = emitter,
            clock = clock,
            bootSessionProvider = { BOOT_SESSION_ID },
            packageInfo = packageInfo,
            logger = logger,
            polygonController = polygonController,
            dwellCoordinator = coordinator
        )
        val services = GeofenceServicesImpl(
            repository = repository,
            secureUserStore = secureUserStore,
            regionStore = store,
            cooldownFilter = cooldownFilter,
            polygonController = polygonController,
            scope = this,
            logger = logger,
            permissionChecker = permissionChecker,
            clock = clock
        )
        val pipeline = GeofenceCrossingPipeline(
            regionStore = store,
            services = services,
            registrar = registrar,
            transitionProcessor = processor,
            dwellCoordinator = coordinator,
            polygonController = polygonController,
            logger = logger
        )
        return Wiring(this, services, pipeline)
    }

    private inner class Wiring(
        private val scope: TestScope,
        private val services: GeofenceServices,
        private val pipeline: GeofenceCrossingPipeline
    ) {
        /** The host asked for a refresh and its fix arrived, as ModuleGeofence forwards one. */
        fun refreshFromHostFix(quality: GeofenceFixQuality) {
            services.onRefreshRequested()
            services.onLocationAcquired(FIX_LATITUDE, CIRCLE_LONGITUDE, quality)
            scope.advanceUntilIdle()
        }

        /**
         * GMS decides the arrival from a fix after registration, then reports DWELL once its
         * loitering delay has passed. Returns the ENTER's wall receipt, which entry timing reports.
         */
        suspend fun enterThenDwell(): Long {
            nowElapsedMs += 30_000L
            val entryFixElapsedMs = nowElapsedMs - 5_000L
            val enteredAtSeconds = wallSeconds()
            pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, GMS_TRANSITION_ENTER, entryFixElapsedMs))
            nowElapsedMs += DWELL_RECEIPT_GAP_SECONDS * MILLIS_PER_SECOND
            pipeline.handle(
                crossing(GeofenceCrossingTransition.DWELL, GMS_TRANSITION_DWELL, entryFixElapsedMs + 60_000L)
            )
            return enteredAtSeconds
        }
    }

    private fun dwell(): PendingGeofenceDelivery =
        outbox.loadAll().single { it.transition == Event.GeofenceTransition.DWELL }

    /** Still emitted once, since GMS's DWELL proves the threshold, but claims nothing about arrival. */
    private fun PendingGeofenceDelivery.assertNoEntryTiming() {
        enteredAt.shouldBeNull()
        dwellDurationSeconds.shouldBeNull()
    }

    private fun fixQuality(accuracyMeters: Float?) = GeofenceFixQuality(
        fixElapsedRealtimeMillis = nowElapsedMs - 1_000L,
        horizontalAccuracyMeters = accuracyMeters
    )

    private fun crossing(
        transition: GeofenceCrossingTransition,
        rawTransitionCode: Int,
        fixElapsedMs: Long
    ) = GeofenceCrossing(
        geofenceIds = listOf(CIRCLE_ID),
        transition = transition,
        transitionName = transition.name,
        rawTransitionCode = rawTransitionCode,
        latitude = CIRCLE_LATITUDE,
        longitude = CIRCLE_LONGITUDE,
        triggeringLocation = Location("fused").apply {
            latitude = CIRCLE_LATITUDE
            longitude = CIRCLE_LONGITUDE
            accuracy = ACCURATE_METERS
            elapsedRealtimeNanos = fixElapsedMs * NANOS_PER_MILLI
            time = WALL_CLOCK_AT_BOOT_MS + fixElapsedMs
        },
        receivedAtSeconds = wallSeconds(),
        receivedAtElapsedMs = nowElapsedMs
    )

    private fun wallSeconds() = (WALL_CLOCK_AT_BOOT_MS + nowElapsedMs) / MILLIS_PER_SECOND

    /** 100 m with a 60 s dwell threshold, on default transition types. */
    private fun circle() = GeofenceRegion(
        id = CIRCLE_ID,
        latitude = CIRCLE_LATITUDE,
        longitude = CIRCLE_LONGITUDE,
        radius = 100f,
        dwellThresholdSeconds = 60
    )

    private companion object {
        const val USER_ID = "user-1"
        const val CIRCLE_ID = "store"
        const val BOOT_SESSION_ID = "boot"
        const val CIRCLE_LATITUDE = 37.7750
        const val CIRCLE_LONGITUDE = -122.4194

        /** ~600 m north of the centre, so ~500 m beyond the edge. */
        const val FIX_LATITUDE = 37.7804
        const val ACCURATE_METERS = 10f
        const val DWELL_RECEIPT_GAP_SECONDS = 90L

        // Geofence.GEOFENCE_TRANSITION_ENTER and GEOFENCE_TRANSITION_DWELL.
        const val GMS_TRANSITION_ENTER = 1
        const val GMS_TRANSITION_DWELL = 4

        const val START_ELAPSED_MS = 10_000_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val NANOS_PER_MILLI = 1_000_000L

        /** Far from zero, so a wall stamp can't pass for an unset one. */
        const val WALL_CLOCK_AT_BOOT_MS = 1_760_000_000_000L
    }
}

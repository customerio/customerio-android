package io.customer.geofence

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.commontest.util.ScopeProviderStub
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.location.LocationCoordinates
import io.customer.location.LocationServices
import io.customer.location.ModuleLocation
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.data.store.PendingDeliveryFlusher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(InternalCustomerIOApi::class)
@RunWith(RobolectricTestRunner::class)
class ModuleGeofenceTest : RobolectricTest() {

    private val mockLocationServices: LocationServices = mockk(relaxed = true)
    private val mockLocationModule: ModuleLocation = mockk {
        every { locationServices } returns mockLocationServices
    }

    // 'mock' prefix avoids shadowing the AndroidSDKComponent extension accessors
    // inside the `android { ... }` override lambda.
    private val mockManager: GeofenceManager = mockk(relaxed = true)
    private val mockStore = mockk<GeofenceRegionStore>(relaxed = true)
    private val mockCooldownFilter: GeofenceCooldownFilter = mockk(relaxed = true)
    private val mockDeliveryFlusher: PendingDeliveryFlusher<PendingGeofenceDelivery> = mockk(relaxed = true)

    private val mockLogger: GeofenceLogger = mockk(relaxed = true)

    // Queues the teardown coroutine instead of running it, so tests can assert what happened
    // before it ran.
    private val scopeProviderStub = ScopeProviderStub.Standard()

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    sdk {
                        overrideDependency<ScopeProvider>(scopeProviderStub)
                        overrideDependency<GeofenceLogger>(mockLogger)
                    }
                    android {
                        overrideDependency<GeofenceManager>(mockManager)
                        overrideDependency<GeofenceRegionStore>(mockStore)
                        overrideDependency<GeofenceCooldownFilter>(mockCooldownFilter)
                        overrideDependency<PendingDeliveryFlusher<PendingGeofenceDelivery>>(mockDeliveryFlusher)
                    }
                }
            }
        )
        coEvery { mockManager.clearAll() } returns Result.success(Unit)
    }

    private fun moduleWith(mode: GeofenceLocationMode) =
        ModuleGeofence(GeofenceModuleConfig.Builder().setLocationMode(mode).build())

    @Test
    fun initialize_givenOffMode_expectStoreClearedSynchronouslyAndOsClearDeferred() {
        // OFF must actively tear down a prior run's state, not just skip setup — the registrations
        // and receivers survive app updates and fire on their own.
        moduleWith(GeofenceLocationMode.OFF).initialize()

        // Store first, before initialize returns: the receivers run on their own scopes later in
        // this launch, and surviving registeredIds/anchors are what they would re-register from.
        verify(exactly = 1) { mockStore.clearUserScopedState() }
        coVerify(exactly = 0) { mockManager.clearAll() }

        scopeProviderStub.geofenceScope.testScheduler.advanceUntilIdle()

        verify(exactly = 1) { mockCooldownFilter.clearAll() }
        coVerify(exactly = 1) { mockManager.clearAll() }
    }

    @Test
    fun initialize_givenOffMode_expectPendingDeliveriesStillFlushed() {
        // Rows are transitions that already happened; nothing else drains one whose worker
        // enqueue failed.
        moduleWith(GeofenceLocationMode.OFF).initialize()
        scopeProviderStub.geofenceScope.testScheduler.advanceUntilIdle()

        verify(exactly = 1) { mockDeliveryFlusher.flush(any(), any(), any()) }
    }

    @Test
    fun refreshFromCurrentLocation_givenOffMode_expectNoOp() {
        moduleWith(GeofenceLocationMode.OFF).refreshFromCurrentLocation()

        // MANUAL is the control: without the OFF guard both modes reach the module lookup and log.
        verify(exactly = 0) { mockLogger.logMissingLocationModule() }
        moduleWith(GeofenceLocationMode.MANUAL).refreshFromCurrentLocation()
        verify(exactly = 1) { mockLogger.logMissingLocationModule() }
    }

    @Test
    fun autoAcquireIfNeeded_givenNoLocationAndAutomatic_expectSilentFetch() {
        moduleWith(GeofenceLocationMode.AUTOMATIC).autoAcquireIfNeeded(mockLocationModule, currentLocation = null)

        verify { mockLocationServices.requestLocationUpdateSilently() }
    }

    @Test
    fun autoAcquireIfNeeded_givenNoLocationAndManual_expectNoFetch() {
        moduleWith(GeofenceLocationMode.MANUAL).autoAcquireIfNeeded(mockLocationModule, currentLocation = null)

        verify(exactly = 0) { mockLocationServices.requestLocationUpdateSilently() }
    }

    @Test
    fun autoAcquireIfNeeded_givenLocationAlreadyAvailable_expectNoFetch() {
        moduleWith(GeofenceLocationMode.AUTOMATIC)
            .autoAcquireIfNeeded(mockLocationModule, currentLocation = LocationCoordinates(latitude = 1.0, longitude = 2.0))

        verify(exactly = 0) { mockLocationServices.requestLocationUpdateSilently() }
    }

    @Test
    fun resolveAnchor_givenRegistrationCenter_expectItPreferredOverLastKnown() {
        val anchor = moduleWith(GeofenceLocationMode.AUTOMATIC).resolveAnchor(
            registrationCenter = GeofenceLocation(latitude = 10.0, longitude = 20.0),
            lastKnown = LocationCoordinates(latitude = 1.0, longitude = 2.0)
        )

        anchor shouldBeEqualTo LocationCoordinates(latitude = 10.0, longitude = 20.0)
    }

    @Test
    fun resolveAnchor_givenNoRegistrationCenter_expectFallsBackToLastKnown() {
        val anchor = moduleWith(GeofenceLocationMode.AUTOMATIC).resolveAnchor(
            registrationCenter = null,
            lastKnown = LocationCoordinates(latitude = 1.0, longitude = 2.0)
        )

        anchor shouldBeEqualTo LocationCoordinates(latitude = 1.0, longitude = 2.0)
    }

    @Test
    fun resolveAnchor_givenNeither_expectNull() {
        moduleWith(GeofenceLocationMode.AUTOMATIC)
            .resolveAnchor(registrationCenter = null, lastKnown = null)
            .shouldBeNull()
    }
}

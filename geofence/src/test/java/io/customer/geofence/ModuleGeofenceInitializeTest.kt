package io.customer.geofence

import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.commontest.util.ScopeProviderStub
import io.customer.geofence.polygon.PolygonGeofenceServiceController
import io.customer.location.LocationServices
import io.customer.location.ModuleLocation
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `initialize()` is called inline from the host's `Application.onCreate`, so anything it does
 * lands on the main thread before the host's own startup continues. Applying the polygon tracking
 * mode reads and writes SharedPreferences, decrypts the identified user through the Keystore and
 * asks the package manager about the foreground service — none of which may block that thread.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ModuleGeofenceInitializeTest : RobolectricTest() {
    private val polygonController: PolygonGeofenceServiceController = mockk(relaxed = true)
    private val scopeProviderStub = ScopeProviderStub.Standard()
    private val locationServices: LocationServices = mockk(relaxed = true)
    private val locationModule: ModuleLocation = mockk(relaxed = true) {
        every { moduleName } returns ModuleLocation.MODULE_NAME
        every { this@mockk.locationServices } returns this@ModuleGeofenceInitializeTest.locationServices
    }

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    sdk { overrideDependency<ScopeProvider>(scopeProviderStub) }
                    android {
                        overrideDependency<PolygonGeofenceServiceController>(polygonController)
                    }
                }
            }
        )
        SDKComponent.modules[ModuleLocation.MODULE_NAME] = locationModule
    }

    @After
    fun removeLocationModule() {
        SDKComponent.modules.remove(ModuleLocation.MODULE_NAME)
    }

    @Test
    fun initialize_expectTrackingModeAppliedOffTheCallingThread() {
        val module = ModuleGeofence(
            GeofenceModuleConfig.Builder()
                .setPolygonTrackingMode(PolygonTrackingMode.CONTINUOUS)
                .build()
        )

        module.initialize()

        // Nothing blocking has run yet: the queued work is still sitting on the background scope.
        verify(exactly = 0) { polygonController.setTrackingMode(any()) }

        (scopeProviderStub.geofenceScope as TestScope).advanceUntilIdle()

        verify { polygonController.setTrackingMode(PolygonTrackingMode.CONTINUOUS) }
    }

    @Test
    fun initialize_givenLocationModuleMissing_expectMonitoringStoppedOffTheCallingThread() {
        SDKComponent.modules.remove(ModuleLocation.MODULE_NAME)
        val module = ModuleGeofence()

        module.initialize()

        verify(exactly = 0) { polygonController.setTrackingMode(any()) }
        verify(exactly = 0) { polygonController.stopAll() }

        (scopeProviderStub.geofenceScope as TestScope).advanceUntilIdle()

        verify { polygonController.setTrackingMode(PolygonTrackingMode.RESPONSIVE) }
        verify { polygonController.stopAll() }
    }
}

package io.customer.geofence

import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.commontest.util.ScopeProviderStub
import io.customer.location.ModuleLocation
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.communication.EventBusImpl
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Publishes on the real bus to the subscription [ModuleGeofence.initialize] installs. */
@RunWith(RobolectricTestRunner::class)
class ModuleGeofenceLocationFixTest : RobolectricTest() {
    private val services: GeofenceServices = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { argument(ApplicationArgument(applicationMock)) })
        SDKComponent.overrideDependency<ScopeProvider>(ScopeProviderStub.Unconfined())
        SDKComponent.overrideDependency<EventBus>(EventBusImpl())
        SDKComponent.overrideDependency<GeofenceLogger>(mockk(relaxed = true))
        SDKComponent.android().overrideDependency<GeofenceServices>(services)
        SDKComponent.android().overrideDependency<GeofencePermissionReporter>(mockk(relaxed = true))
        ModuleLocation().let { SDKComponent.modules[it.moduleName] = it }
        ModuleGeofence().initialize()
    }

    @After
    fun resetSdkComponent() {
        SDKComponent.reset()
    }

    @Test
    fun locationFix_givenTimeAndAccuracy_expectBothReachServices() {
        SDKComponent.eventBus.publish(
            Event.LocationFixAcquired(
                latitude = 1.5,
                longitude = 2.5,
                fixElapsedRealtimeMillis = 1_234L,
                horizontalAccuracyMeters = 12f
            )
        )

        verify(exactly = 1) { services.onLocationAcquired(1.5, 2.5, GeofenceFixQuality(1_234L, 12f)) }
    }

    @Test
    fun locationFix_givenNoAccuracy_expectAccuracyStaysUnknown() {
        SDKComponent.eventBus.publish(
            Event.LocationFixAcquired(latitude = 1.5, longitude = 2.5, fixElapsedRealtimeMillis = 1_234L)
        )

        verify(exactly = 1) { services.onLocationAcquired(1.5, 2.5, GeofenceFixQuality(1_234L, null)) }
    }
}

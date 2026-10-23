package io.customer.geofence

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.location.ModuleLocation
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModuleGeofenceTest : RobolectricTest() {

    private val mockServices = mockk<GeofenceServices>(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    android {
                        overrideDependency<GeofenceServices>(mockServices)
                    }
                }
            }
        )
    }

    @OptIn(InternalCustomerIOApi::class)
    @Test
    fun instance_givenRegisteredButNotInitialized_expectCallsDoNothingInsteadOfCrashing() {
        // The SDK registers modules without setting them up when it refuses a secret key
        val givenLocation = ModuleLocation().attachToSDKComponent()
        val givenGeofence = ModuleGeofence().attachToSDKComponent()

        ModuleLocation.instance() shouldBe givenLocation
        ModuleGeofence.instance() shouldBe givenGeofence

        ModuleLocation.instance().locationServices.setLastKnownLocation(37.7749, -122.4194)
        ModuleLocation.instance().locationServices.requestLocationUpdate()
        ModuleGeofence.instance().refreshFromCurrentLocation()

        verify(exactly = 0) { mockServices.onLocationAcquired(any(), any(), any()) }

        ModuleLocation.instance().locationServices.getLastKnownLocation().shouldBeNull()
    }
}

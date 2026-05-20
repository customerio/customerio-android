package io.customer.location.geofence

import android.Manifest
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class GeofencePermissionCheckerTest : RobolectricTest() {

    private lateinit var checker: GeofencePermissionChecker

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
            }
        )
        checker = GeofencePermissionChecker(applicationMock)
    }

    @Test
    @Config(sdk = [29])
    fun hasRequiredLocationPermissions_givenFineGranted_expectTrueEvenWithoutBackground() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        deny(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        checker.hasRequiredLocationPermissions().shouldBeTrue()
    }

    @Test
    @Config(sdk = [29])
    fun hasRequiredLocationPermissions_givenFineDenied_expectFalse() {
        deny(Manifest.permission.ACCESS_FINE_LOCATION)
        grant(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        checker.hasRequiredLocationPermissions().shouldBeFalse()
    }

    @Test
    @Config(sdk = [29])
    fun isBackgroundDeliveryAvailable_givenBackgroundGranted_expectTrue() {
        grant(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        checker.isBackgroundDeliveryAvailable().shouldBeTrue()
    }

    @Test
    @Config(sdk = [29])
    fun isBackgroundDeliveryAvailable_givenBackgroundDenied_expectFalse() {
        deny(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        checker.isBackgroundDeliveryAvailable().shouldBeFalse()
    }

    @Test
    @Config(sdk = [28])
    fun isBackgroundDeliveryAvailable_belowQ_expectTrueRegardlessOfPermission() {
        // Pre-Q, FINE covers background delivery implicitly.
        deny(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        checker.isBackgroundDeliveryAvailable().shouldBeTrue()
    }

    private fun grant(permission: String) {
        shadowOf(applicationMock).grantPermissions(permission)
    }

    private fun deny(permission: String) {
        shadowOf(applicationMock).denyPermissions(permission)
    }
}

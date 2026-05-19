package io.customer.location.geofence

import android.content.Intent
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceBootReceiverTest : RobolectricTest() {

    private val mockRepository: GeofenceRepository = mockk(relaxed = true)
    private val mockPermissionChecker: GeofencePermissionChecker = mockk(relaxed = true)

    private lateinit var receiver: GeofenceBootReceiver

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    android {
                        overrideDependency<GeofenceRepository>(mockRepository)
                        overrideDependency<GeofencePermissionChecker>(mockPermissionChecker)
                    }
                }
            }
        )
        // Default: permissions granted. Suppression tests override this.
        every { mockPermissionChecker.hasRequiredLocationPermissions() } returns true
        receiver = GeofenceBootReceiver()
    }

    @Test
    fun onReceive_givenNonBootIntent_expectRestoreNotCalled() = runTest {
        // Stray intents on a re-enabled receiver must not trigger a restore.
        receiver.onReceive(applicationMock, Intent("com.example.OTHER_ACTION"))

        coVerify(exactly = 0) { mockRepository.restoreFromCache() }
    }

    @Test
    fun restore_givenPermissionsGranted_expectRepositoryRestoreCalled() = runTest {
        coEvery { mockRepository.restoreFromCache() } returns Result.success(Unit)

        receiver.restore()

        coVerify { mockRepository.restoreFromCache() }
    }

    @Test
    fun restore_givenPermissionsRevoked_expectRepositoryNotCalled() = runTest {
        // Permissions can be revoked while the device is powered off; the boot
        // receiver must fail fast rather than relying on the manager's deeper guard.
        every { mockPermissionChecker.hasRequiredLocationPermissions() } returns false

        receiver.restore()

        coVerify(exactly = 0) { mockRepository.restoreFromCache() }
    }
}

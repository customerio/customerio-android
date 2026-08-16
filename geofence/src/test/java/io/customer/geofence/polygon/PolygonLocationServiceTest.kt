package io.customer.geofence.polygon

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class PolygonLocationServiceTest : RobolectricTest() {
    private val engine: PolygonLocationEngine = mockk(relaxed = true)
    private val polygonController: PolygonGeofenceServiceController = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    android {
                        overrideDependency<PolygonLocationEngine>(engine)
                        overrideDependency<PolygonGeofenceServiceController>(polygonController)
                    }
                }
            }
        )
        every { polygonController.startEngineForService(any()) } returns 7L
        shadowOf(applicationMock).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
    }

    @Test
    fun onCreate_givenPermissionsAndActiveSession_expectForegroundNotificationAndLocationEngine() {
        val controller = Robolectric.buildService(PolygonLocationService::class.java).create()
        val service = controller.get()
        service.onStartCommand(serviceStartIntent(11L), 0, 1)

        verify { polygonController.onServicePromoted(11L) }
        verify { polygonController.startEngineForService(any()) }
        val manager = applicationMock.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.size shouldBeEqualTo 1

        controller.destroy()
        verify { engine.stopIfCurrent(7L) }
        verify { polygonController.onServiceDestroyed(11L) }
    }

    @Test
    fun onStartCommand_expectStickyRecoveryMode() {
        val service = Robolectric.buildService(PolygonLocationService::class.java).create().get()

        service.onStartCommand(serviceStartIntent(1L), 0, 1) shouldBeEqualTo android.app.Service.START_STICKY
    }

    @Test
    fun onCreate_givenFineLocationPermissionMissing_expectEngineDoesNotStart() {
        shadowOf(applicationMock).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        Robolectric.buildService(PolygonLocationService::class.java).create()

        verify(exactly = 0) { polygonController.startEngineForService(any()) }
    }

    @Test
    fun onCreate_givenPersistedSessionIsNotRoutable_expectServiceStopsWithoutStartingEngine() {
        every { polygonController.startEngineForService(any()) } returns null

        val service = Robolectric.buildService(PolygonLocationService::class.java).create().get()
        service.onStartCommand(serviceStartIntent(1L), 0, 1)

        verify { polygonController.startEngineForService(any()) }
        verify(exactly = 0) { engine.start(any()) }
    }

    private fun serviceStartIntent(generation: Long) = Intent().putExtra(
        PolygonLocationService.EXTRA_SERVICE_START_GENERATION,
        generation
    )
}

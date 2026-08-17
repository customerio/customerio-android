package io.customer.geofence.polygon

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import androidx.core.app.ServiceCompat
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class PolygonLocationServiceTest : RobolectricTest() {
    private val fineStream: PolygonFineLocationStream = mockk(relaxed = true)
    private val polygonController: PolygonGeofenceServiceController = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    android {
                        overrideDependency<PolygonFineLocationStream>(fineStream)
                        overrideDependency<PolygonGeofenceServiceController>(polygonController)
                    }
                }
            }
        )
        every { polygonController.isContinuousTrackingEnabled() } returns true
        every { polygonController.startFineLocationStream(any()) } returns 7L
        shadowOf(applicationMock).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
    }

    @After
    fun unmockPromotion() {
        unmockkStatic(ServiceCompat::class)
    }

    @Test
    fun onStartCommand_givenPromotionSucceeds_expectAcknowledgedBeforeStreamRegistration() {
        val controller = Robolectric.buildService(PolygonLocationService::class.java).create()
        val service = controller.get()

        service.onStartCommand(serviceStartIntent(11L), 0, 1) shouldBeEqualTo Service.START_STICKY

        verifyOrder {
            polygonController.onServicePromoted(11L)
            polygonController.startFineLocationStream(any())
        }
        val manager = applicationMock.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.size shouldBeEqualTo 1

        controller.destroy()
        verify { fineStream.stopIfCurrent(7L) }
        verify { polygonController.onServiceDestroyed(11L) }
    }

    @Test
    fun onStartCommand_givenAndroidRefusesPromotion_expectNoPromotedLatchAndNoStream() {
        mockkStatic(ServiceCompat::class)
        every { ServiceCompat.startForeground(any(), any(), any(), any()) } throws
            IllegalStateException("foreground service start not allowed")

        val service = Robolectric.buildService(PolygonLocationService::class.java).create().get()

        service.onStartCommand(serviceStartIntent(11L), 0, 1) shouldBeEqualTo Service.START_NOT_STICKY

        verify {
            polygonController.onServicePromotionFailed(
                11L,
                "foreground service start not allowed"
            )
        }
        verify(exactly = 0) { polygonController.onServicePromoted(any()) }
        verify(exactly = 0) { polygonController.startFineLocationStream(any()) }
        verify(exactly = 0) { fineStream.start(any()) }
    }

    @Test
    fun onStartCommand_givenFineLocationPermissionMissing_expectPromotionFailureAndNoStream() {
        shadowOf(applicationMock).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        val service = Robolectric.buildService(PolygonLocationService::class.java).create().get()

        service.onStartCommand(serviceStartIntent(11L), 0, 1) shouldBeEqualTo Service.START_NOT_STICKY

        verify {
            polygonController.onServicePromotionFailed(11L, "ACCESS_FINE_LOCATION is not granted")
        }
        verify(exactly = 0) { polygonController.startFineLocationStream(any()) }
    }

    @Test
    fun onStartCommand_givenResponsiveMode_expectNoPromotionAndNoNotification() {
        every { polygonController.isContinuousTrackingEnabled() } returns false

        val service = Robolectric.buildService(PolygonLocationService::class.java).create().get()

        service.onStartCommand(serviceStartIntent(11L), 0, 1) shouldBeEqualTo Service.START_NOT_STICKY

        verify(exactly = 0) { polygonController.onServicePromoted(any()) }
        verify(exactly = 0) { polygonController.startFineLocationStream(any()) }
        val manager = applicationMock.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.size shouldBeEqualTo 0
    }

    @Test
    fun onStartCommand_givenSessionIsNoLongerRoutable_expectPromotesThenStopsWithoutStream() {
        every { polygonController.startFineLocationStream(any()) } returns null

        val controller = Robolectric.buildService(PolygonLocationService::class.java).create()
        val service = controller.get()
        service.onStartCommand(serviceStartIntent(11L), 0, 1)

        verify { polygonController.startFineLocationStream(any()) }
        shadowOf(service).isStoppedBySelf shouldBeEqualTo true
        verify(exactly = 0) { fineStream.start(any()) }

        // Nothing was registered, so teardown must not claim a stream generation.
        controller.destroy()
        verify(exactly = 0) { fineStream.stopIfCurrent(any()) }
    }

    @Test
    fun onStartCommand_givenStickyRestartWithoutIntent_expectRevalidatesWithoutAcknowledgingPromotion() {
        val service = Robolectric.buildService(PolygonLocationService::class.java).create().get()

        service.onStartCommand(null, 0, 1) shouldBeEqualTo Service.START_STICKY

        verify(exactly = 0) { polygonController.onServicePromoted(any()) }
        verify { polygonController.startFineLocationStream(any()) }
        val manager = applicationMock.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.size shouldBeEqualTo 1
    }

    private fun serviceStartIntent(generation: Long) = Intent().putExtra(
        PolygonLocationService.EXTRA_SERVICE_START_GENERATION,
        generation
    )
}

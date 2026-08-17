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
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.PolygonTrackingMode
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    private val store: GeofenceRegionStore = mockk(relaxed = true)
    private val engine: PolygonLocationEngine = mockk(relaxed = true)
    private val approachMonitor: PolygonApproachMonitor = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private val continuousModeValidator: PolygonContinuousModeValidator = mockk(relaxed = true)

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
        every { polygonController.isContinuousTrackingModeSnapshot() } returns true
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
        every { polygonController.isContinuousTrackingModeSnapshot() } returns false

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

    @Test
    fun onStartCommand_givenControllerLockHeldByCatalogRecovery_expectPromotesWithoutWaitingForIt() {
        val realController = installRealController()
        val lockHeld = AtomicBoolean(false)
        val lockAcquired = CountDownLatch(1)
        val promotionReached = CountDownLatch(1)
        val promotedWhileLockHeld = AtomicBoolean(false)
        // recover() holds controllerLock across a full-catalog decode, so blocking inside that decode
        // reproduces exactly what the service start path must not wait on.
        every { store.getCachedRegions() } answers {
            lockHeld.set(true)
            lockAcquired.countDown()
            promotionReached.await(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)
            lockHeld.set(false)
            emptyList()
        }
        mockkStatic(ServiceCompat::class)
        every { ServiceCompat.startForeground(any(), any(), any(), any()) } answers {
            promotedWhileLockHeld.set(lockHeld.get())
            promotionReached.countDown()
            callOriginal()
        }
        val serviceController = Robolectric.buildService(PolygonLocationService::class.java).create()
        val service = serviceController.get()
        val lockHolder = Thread({ realController.recover() }, "controller-lock-holder")
        lockHolder.start()
        lockAcquired.await(LOCK_WAIT_SECONDS, TimeUnit.SECONDS) shouldBeEqualTo true

        service.onStartCommand(serviceStartIntent(11L), 0, 1) shouldBeEqualTo Service.START_STICKY

        // The point of the regression: promotion happened while another thread still held the lock,
        // so the pre-promotion mode read cannot push startForeground past the Android deadline.
        promotedWhileLockHeld.get() shouldBeEqualTo true
        lockHolder.join(TimeUnit.SECONDS.toMillis(LOCK_WAIT_SECONDS))
        lockHolder.isAlive shouldBeEqualTo false
        val manager = applicationMock.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.size shouldBeEqualTo 1
        verify { fineStream.start(any()) }
    }

    @Test
    fun onStartCommand_givenModeSwitchedToResponsiveAfterSnapshot_expectPromotesThenStopsWithoutSampling() {
        installRealController()
        // The lock-free snapshot can be one write stale: it reads CONTINUOUS, and the switch back to
        // responsive lands before the locked recheck that decides whether anything is sampled.
        var mode = PolygonTrackingMode.CONTINUOUS
        every { store.getPolygonTrackingMode() } answers {
            mode.also { mode = PolygonTrackingMode.RESPONSIVE }
        }
        val serviceController = Robolectric.buildService(PolygonLocationService::class.java).create()
        val service = serviceController.get()

        service.onStartCommand(serviceStartIntent(11L), 0, 1)

        // Promoted, because the process owed the system a startForeground for the start it requested.
        val manager = applicationMock.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.size shouldBeEqualTo 1
        // But nothing is sampled, and the service takes itself down.
        verify(exactly = 0) { fineStream.start(any()) }
        verify { fineStream.stop() }
        shadowOf(service).isStoppedBySelf shouldBeEqualTo true
    }

    /**
     * Replaces the mocked controller with a real one, so the lock regressions run against the actual
     * `controllerLock` rather than a mock that never takes it.
     */
    private fun installRealController(): PolygonGeofenceServiceController {
        every { store.userStateGeneration() } returns 0L
        every { store.activeUserSessionId() } returns "user-1"
        every { secureUserStore.getUserId() } returns "user-1"
        every { store.getRoutableRegisteredIds() } returns setOf("campus")
        every { store.getActivePolygonIds() } returns setOf("campus")
        every { store.getPolygonTrackingMode() } returns PolygonTrackingMode.CONTINUOUS
        every { continuousModeValidator.configurationError() } returns null
        every { fineStream.start(any()) } returns 7L
        val realController = PolygonGeofenceServiceController(
            context = applicationMock,
            store = store,
            engine = engine,
            fineStream = fineStream,
            approachMonitor = approachMonitor,
            secureUserStore = secureUserStore,
            logger = logger,
            continuousModeValidator = continuousModeValidator
        )
        SDKComponent.android().overrideDependency<PolygonGeofenceServiceController>(realController)
        return realController
    }

    private fun serviceStartIntent(generation: Long) = Intent().putExtra(
        PolygonLocationService.EXTRA_SERVICE_START_GENERATION,
        generation
    )

    private companion object {
        const val LOCK_WAIT_SECONDS = 10L
    }
}

package io.customer.location

import io.customer.location.store.LocationPreferenceStore
import io.customer.location.sync.LocationSyncFilter
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.pipeline.DataPipeline
import io.customer.sdk.core.util.Logger
import io.customer.sdk.util.EventNames
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeEmpty
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LocationTrackerTest {

    private val dataPipeline: DataPipeline = mockk(relaxUnitFun = true)
    private val store: LocationPreferenceStore = mockk(relaxUnitFun = true)
    private val syncFilter: LocationSyncFilter = mockk(relaxUnitFun = true)
    private val mockEventBus: EventBus = mockk(relaxUnitFun = true)
    private val logger: Logger = mockk(relaxUnitFun = true)

    private val restoreScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var tracker: LocationTracker

    // Stands in for the persisted cache, which resetContext() clears.
    private var hasCachedLocation = true

    @BeforeEach
    fun setup() {
        every { store.clearCachedLocation() } answers { hasCachedLocation = false }
        every { store.getCachedLatitude() } returns null
        every { store.getCachedLongitude() } returns null
        every { dataPipeline.isUserIdentified } returns true
        every { syncFilter.filterAndRecord(any(), any()) } returns true
        tracker = LocationTracker(dataPipeline, store, syncFilter, mockEventBus, logger, restoresPersistedLocation = true, restoreScope = restoreScope)
    }

    @AfterEach
    fun teardown() {
        restoreScope.cancel()
    }

    // -- onLocationReceived --

    @Test
    fun givenLocationReceived_expectPersistsToStore() {
        tracker.onLocationReceived(37.7749, -122.4194)

        verify { store.saveCachedLocation(37.7749, -122.4194) }
    }

    @Test
    fun givenLocationReceived_userIdentified_filterPasses_expectTrackCalled() {
        tracker.onLocationReceived(37.7749, -122.4194)

        verify {
            dataPipeline.track(
                name = EventNames.LOCATION_UPDATE,
                properties = mapOf("latitude" to 37.7749, "longitude" to -122.4194)
            )
        }
    }

    @Test
    fun givenLocationReceived_noUserId_expectTrackNotCalled() {
        every { dataPipeline.isUserIdentified } returns false

        tracker.onLocationReceived(37.7749, -122.4194)

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    @Test
    fun givenLocationReceived_filterRejects_expectTrackNotCalled() {
        every { syncFilter.filterAndRecord(any(), any()) } returns false

        tracker.onLocationReceived(37.7749, -122.4194)

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    @Test
    fun givenNullDataPipeline_expectNoException() {
        val trackerWithNullPipeline = LocationTracker(null, store, syncFilter, mockEventBus, logger, restoresPersistedLocation = true, restoreScope = restoreScope)

        trackerWithNullPipeline.onLocationReceived(37.7749, -122.4194)

        // Persist still happens, but no track call
        verify { store.saveCachedLocation(37.7749, -122.4194) }
    }

    @Test
    fun givenLocationReceived_expectLocationAcquiredPublishedOnEventBus() {
        val captured = slot<Event.LocationAcquired>()

        tracker.onLocationReceived(37.7749, -122.4194)

        verify { mockEventBus.publish(capture(captured)) }
        captured.captured shouldBeEqualTo Event.LocationAcquired(latitude = 37.7749, longitude = -122.4194)
    }

    @Test
    fun givenLocationReceived_expectFixQualityPublishedSeparately() {
        // Quality rides its own event so LocationAcquired keeps its published shape.
        val captured = mutableListOf<Event>()

        tracker.onLocationReceived(
            latitude = 37.7749,
            longitude = -122.4194,
            fixElapsedRealtimeMillis = 90_000L,
            horizontalAccuracyMeters = 12f
        )

        verify { mockEventBus.publish(capture(captured)) }
        captured.filterIsInstance<Event.LocationAcquired>().single() shouldBeEqualTo
            Event.LocationAcquired(latitude = 37.7749, longitude = -122.4194)
        captured.filterIsInstance<Event.LocationFixAcquired>().single() shouldBeEqualTo
            Event.LocationFixAcquired(
                latitude = 37.7749,
                longitude = -122.4194,
                fixElapsedRealtimeMillis = 90_000L,
                horizontalAccuracyMeters = 12f
            )
    }

    // -- onLocationReceivedWithoutTracking --

    @Test
    fun givenLocationReceivedWithoutTracking_expectNoAnalyticsSideEffects() {
        tracker.onLocationReceivedWithoutTracking(37.7749, -122.4194)

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
        verify(exactly = 0) { store.saveCachedLocation(any(), any()) }
        tracker.getIdentifyContext().shouldBeEmpty()
    }

    @Test
    fun givenLocationReceivedWithoutTracking_expectPublishedAndReadableViaLastKnownLocation() {
        val captured = mutableListOf<Event>()

        tracker.onLocationReceivedWithoutTracking(37.7749, -122.4194, 90_000L, 12f)

        verify { mockEventBus.publish(capture(captured)) }
        captured.filterIsInstance<Event.LocationAcquired>().single() shouldBeEqualTo
            Event.LocationAcquired(latitude = 37.7749, longitude = -122.4194)
        captured.filterIsInstance<Event.LocationFixAcquired>().single() shouldBeEqualTo
            Event.LocationFixAcquired(37.7749, -122.4194, 90_000L, 12f)
        tracker.lastKnownLocation shouldBeEqualTo LocationCoordinates(latitude = 37.7749, longitude = -122.4194)
    }

    // -- syncCachedLocationIfNeeded --

    @Test
    fun givenCachedLocationExists_expectTriesSendLocationTrack() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        tracker.syncCachedLocationIfNeeded()

        verify {
            dataPipeline.track(
                name = EventNames.LOCATION_UPDATE,
                properties = mapOf("latitude" to 37.7749, "longitude" to -122.4194)
            )
        }
    }

    @Test
    fun givenNoCachedLatitude_expectNoTrack() {
        every { store.getCachedLatitude() } returns null
        every { store.getCachedLongitude() } returns -122.4194

        tracker.syncCachedLocationIfNeeded()

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    @Test
    fun givenNoCachedLongitude_expectNoTrack() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns null

        tracker.syncCachedLocationIfNeeded()

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    // -- restorePersistedLocation --

    @Test
    fun givenPersistedLocation_expectSetsInMemoryCache() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        tracker.restorePersistedLocation()

        val context = tracker.getIdentifyContext()
        context.shouldNotBeEmpty()
        context["location_latitude"] shouldBeEqualTo 37.7749
        context["location_longitude"] shouldBeEqualTo -122.4194
        // Also seeds the cross-module field so geofencing has a fix at cold start.
        tracker.lastKnownLocation shouldBeEqualTo LocationCoordinates(latitude = 37.7749, longitude = -122.4194)
    }

    @Test
    fun givenNoPersistedLatitude_expectNoContext() {
        every { store.getCachedLatitude() } returns null

        tracker.restorePersistedLocation()

        tracker.getIdentifyContext().shouldBeEmpty()
    }

    @Test
    fun givenNoPersistedLongitude_expectNoContext() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns null

        tracker.restorePersistedLocation()

        tracker.getIdentifyContext().shouldBeEmpty()
    }

    @Test
    fun givenSignOutDuringRestore_expectOldLocationNotRestored() {
        every { store.getCachedLatitude() } answers {
            tracker.resetContext()
            37.7749
        }
        every { store.getCachedLongitude() } returns -122.4194

        tracker.restorePersistedLocation()

        tracker.getIdentifyContext().shouldBeEmpty()
        tracker.lastKnownLocation.shouldBeNull()
    }

    @Test
    fun givenFixDuringRestore_expectNewerFixKept() {
        every { store.getCachedLatitude() } answers {
            tracker.onLocationReceived(51.5074, -0.1278)
            37.7749
        }
        every { store.getCachedLongitude() } returns -122.4194

        tracker.restorePersistedLocation()

        tracker.trackedLocation shouldBeEqualTo LocationCoordinates(latitude = 51.5074, longitude = -0.1278)
        tracker.lastKnownLocation shouldBeEqualTo LocationCoordinates(latitude = 51.5074, longitude = -0.1278)
        verify(exactly = 0) { logger.debug(match { it.startsWith("Restored persisted location") }) }
    }

    @Test
    fun givenSignOutWhileSavingFix_expectSaveDroppedAndNotRestored() {
        every { store.saveCachedLocation(any(), any()) } answers {
            tracker.resetContext()
            hasCachedLocation = true
        }
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        tracker.onLocationReceived(37.7749, -122.4194)
        tracker.restorePersistedLocation()

        hasCachedLocation.shouldBeFalse()
        tracker.trackedLocation.shouldBeNull()
        tracker.lastKnownLocation.shouldBeNull()
    }

    @Test
    fun givenRestoreAndSaveOverlapSignOut_expectNoLocationLeft() {
        val saveWritten = CountDownLatch(1)
        val restoreDone = CountDownLatch(1)
        every { store.saveCachedLocation(any(), any()) } answers {
            tracker.resetContext()
            hasCachedLocation = true
            saveWritten.countDown()
            restoreDone.await(5, TimeUnit.SECONDS)
        }
        every { store.getCachedLatitude() } answers {
            saveWritten.await(5, TimeUnit.SECONDS)
            37.7749
        }
        every { store.getCachedLongitude() } returns -122.4194

        val save = CompletableFuture.runAsync { tracker.onLocationReceived(51.5074, -0.1278) }
        tracker.restorePersistedLocation()
        restoreDone.countDown()
        save.get(5, TimeUnit.SECONDS)

        tracker.trackedLocation.shouldBeNull()
        tracker.lastKnownLocation.shouldBeNull()
        hasCachedLocation.shouldBeFalse()
    }

    @Test
    fun givenSilentFixDuringRestore_expectSilentFixKeptAndRestoredLocationTracked() {
        every { store.getCachedLatitude() } answers {
            tracker.onLocationReceivedWithoutTracking(51.5074, -0.1278)
            37.7749
        }
        every { store.getCachedLongitude() } returns -122.4194

        tracker.restorePersistedLocation()

        tracker.trackedLocation shouldBeEqualTo LocationCoordinates(latitude = 37.7749, longitude = -122.4194)
        tracker.lastKnownLocation shouldBeEqualTo LocationCoordinates(latitude = 51.5074, longitude = -0.1278)
    }

    // -- getIdentifyContext --

    @Test
    fun givenNoLocation_expectReturnsEmptyMap() {
        tracker.getIdentifyContext().shouldBeEmpty()
    }

    @Test
    fun givenLocationReceived_expectReturnsLocationContext() {
        tracker.onLocationReceived(37.7749, -122.4194)

        val context = tracker.getIdentifyContext()
        context.shouldNotBeEmpty()
        context["location_latitude"] shouldBeEqualTo 37.7749
        context["location_longitude"] shouldBeEqualTo -122.4194
    }

    @Test
    fun givenNotRestoredYet_expectIdentifyContextRestoresFirst() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        tracker.getIdentifyContext()["location_latitude"] shouldBeEqualTo 37.7749
    }

    @Test
    fun givenTrackingOff_expectPersistedLocationNotRestored() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194
        val tracker = LocationTracker(dataPipeline, store, syncFilter, mockEventBus, logger, restoresPersistedLocation = false, restoreScope = restoreScope)

        tracker.getIdentifyContext().shouldBeEmpty()
        tracker.restorePersistedLocation()

        tracker.lastKnownLocation.shouldBeNull()
        verify(exactly = 0) { store.getCachedLatitude() }
    }

    @Test
    fun givenRestoreRunningOnAnotherThread_expectIdentifyContextWaitsForIt() {
        val releaseDecrypt = CountDownLatch(1)
        every { store.getCachedLatitude() } answers {
            releaseDecrypt.await(5, TimeUnit.SECONDS)
            37.7749
        }
        every { store.getCachedLongitude() } returns -122.4194

        try {
            val restore = CompletableFuture.runAsync { tracker.restorePersistedLocation() }
            verify(timeout = 5_000) { store.getCachedLatitude() }
            val context = CompletableFuture.supplyAsync { tracker.getIdentifyContext() }

            Thread.sleep(200)
            context.isDone.shouldBeFalse()

            releaseDecrypt.countDown()
            restore.get(5, TimeUnit.SECONDS)
            context.get(5, TimeUnit.SECONDS)["location_latitude"] shouldBeEqualTo 37.7749
            verify(exactly = 1) { store.getCachedLatitude() }
        } finally {
            releaseDecrypt.countDown()
        }
    }

    @Test
    fun givenRestoreStuck_expectIdentifyContextStopsWaiting() {
        val releaseDecrypt = CountDownLatch(1)
        every { store.getCachedLatitude() } answers {
            releaseDecrypt.await(5, TimeUnit.SECONDS)
            37.7749
        }
        every { store.getCachedLongitude() } returns -122.4194
        val tracker = LocationTracker(
            dataPipeline,
            store,
            syncFilter,
            mockEventBus,
            logger,
            restoresPersistedLocation = true,
            restoreScope = restoreScope,
            identifyRestoreWaitMillis = 100
        )

        try {
            CompletableFuture.supplyAsync { tracker.getIdentifyContext() }.get(2, TimeUnit.SECONDS).shouldBeEmpty()

            releaseDecrypt.countDown()
            verify(timeout = 5_000) { logger.debug(match { it.startsWith("Restored persisted location") }) }
            tracker.getIdentifyContext()["location_latitude"] shouldBeEqualTo 37.7749
            verify(exactly = 1) { store.getCachedLatitude() }
        } finally {
            releaseDecrypt.countDown()
        }
    }

    @Test
    fun givenAlreadyRestored_expectNoSecondDecrypt() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        tracker.restorePersistedLocation()
        tracker.getIdentifyContext()
        tracker.restorePersistedLocation()

        verify(exactly = 1) { store.getCachedLatitude() }
    }

    // -- onUserIdentified --

    @Test
    fun givenUserIdentified_withCachedLocation_expectSyncsCachedLocation() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        tracker.onUserIdentified()

        verify {
            dataPipeline.track(
                name = EventNames.LOCATION_UPDATE,
                properties = mapOf("latitude" to 37.7749, "longitude" to -122.4194)
            )
        }
    }

    @Test
    fun givenUserIdentified_withCachedLocation_expectSyncFilterConsulted() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        tracker.onUserIdentified()

        verify { syncFilter.filterAndRecord(37.7749, -122.4194) }
    }

    @Test
    fun givenUserIdentified_filterRejects_expectNoTrack() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194
        every { syncFilter.filterAndRecord(any(), any()) } returns false

        tracker.onUserIdentified()

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    @Test
    fun givenUserIdentified_noCachedLocation_expectNoTrack() {
        every { store.getCachedLatitude() } returns null

        tracker.onUserIdentified()

        verify(exactly = 0) { dataPipeline.track(any(), any()) }
    }

    @Test
    fun givenUserIdentified_expectPersistedLocationRestored() {
        every { store.getCachedLatitude() } returns 37.7749
        every { store.getCachedLongitude() } returns -122.4194

        tracker.onUserIdentified()

        // ModuleLocation's ON_APP_START check reads this right after, to skip a needless request.
        tracker.trackedLocation shouldBeEqualTo LocationCoordinates(latitude = 37.7749, longitude = -122.4194)
    }

    // -- resetContext (synchronous, called by analytics.reset during clearIdentify) --

    @Test
    fun givenResetContext_expectClearsEverything() {
        tracker.onLocationReceived(37.7749, -122.4194)

        tracker.resetContext()

        // Both in-memory fields cleared — no stale data for next identify
        tracker.getIdentifyContext().shouldBeEmpty()
        tracker.lastKnownLocation.shouldBeNull()
        // Persistence and sync filter also cleared synchronously
        verify { store.clearCachedLocation() }
        verify { syncFilter.clearSyncedData() }
    }
}

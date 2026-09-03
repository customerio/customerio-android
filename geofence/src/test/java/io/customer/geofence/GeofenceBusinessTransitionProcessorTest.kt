package io.customer.geofence

import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.communication.Event
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class GeofenceBusinessTransitionProcessorTest {
    private val store: GeofenceRegionStore = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val emitter: GeofenceTransitionEmitter = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private val processor = GeofenceBusinessTransitionProcessor(store, secureUserStore, emitter, logger)

    @Before
    fun setUp() {
        every { secureUserStore.getUserId() } returns "user-1"
        every { store.getCachedRegion("polygon") } returns GeofenceRegion(
            id = "polygon",
            latitude = 0.0,
            longitude = 0.0,
            radius = 100f
        )
        every { store.getEnteredIds() } returns emptySet()
        every { store.hasContainmentRecord() } returns true
        every { store.activeUserSessionId() } returns "user-1"
        every { store.commitBusinessTransition(any(), any(), any(), any(), any()) } returns true
    }

    @Test
    fun process_givenOutboxPersistenceFailure_expectContainmentNotCommittedSoFixCanRetry() = runTest {
        coEvery { emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSIST_FAILED

        processor.process("polygon", Event.GeofenceTransition.ENTER, 100L)

        verify(exactly = 0) { store.commitBusinessTransition(any(), any(), any(), any(), any()) }
    }

    @Test
    fun process_givenOutboxFailureAfterDurableStage_expectProcessorLeavesAtomicStageUntouched() = runTest {
        val staged = io.customer.geofence.store.PendingGeofenceDelivery(
            geofenceId = "polygon",
            transition = Event.GeofenceTransition.ENTER,
            timestamp = 100L,
            userId = "user-1",
            transitionId = "transition-1"
        )
        coEvery { emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSIST_FAILED
        every {
            store.getPendingTransitionEntries("user-1", "polygon", Event.GeofenceTransition.ENTER)
        } returns listOf(staged)

        processor.process("polygon", Event.GeofenceTransition.ENTER, 100L)

        verify(exactly = 0) { store.commitBusinessTransition(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { store.completePendingTransition(any()) }
    }

    @Test
    fun process_givenPersistedAtomicStage_expectCommitsAndClearsSameAttempt() = runTest {
        val staged = io.customer.geofence.store.PendingGeofenceDelivery(
            geofenceId = "polygon",
            transition = Event.GeofenceTransition.ENTER,
            timestamp = 100L,
            userId = "user-1",
            transitionId = "transition-1"
        )
        coEvery { emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        every {
            store.getPendingTransitionEntries("user-1", "polygon", Event.GeofenceTransition.ENTER)
        } returns listOf(staged)

        processor.process("polygon", Event.GeofenceTransition.ENTER, 100L)

        verify {
            store.commitBusinessTransition(
                "polygon",
                Event.GeofenceTransition.ENTER,
                "transition-1",
                0L,
                any()
            )
        }
    }

    @Test
    fun process_givenOlderSameDirectionStage_expectCommitsNewestPhysicalAttempt() = runTest {
        val older = io.customer.geofence.store.PendingGeofenceDelivery(
            geofenceId = "polygon",
            transition = Event.GeofenceTransition.ENTER,
            timestamp = 90L,
            userId = "user-1",
            transitionId = "transition-old"
        )
        val newest = older.copy(timestamp = 100L, transitionId = "transition-new")
        coEvery {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns GeofenceTransitionEmitter.Result.PERSISTED
        every {
            store.getPendingTransitionEntries("user-1", "polygon", Event.GeofenceTransition.ENTER)
        } returns listOf(older, newest)

        processor.process("polygon", Event.GeofenceTransition.ENTER, 100L)

        verify {
            store.commitBusinessTransition(
                "polygon",
                Event.GeofenceTransition.ENTER,
                "transition-new",
                0L,
                any()
            )
        }
    }

    @Test
    fun process_givenDetectionFromReplacedGeometry_expectDroppedBeforeEmission() = runTest {
        val current = requireNotNull(store.getCachedRegion("polygon"))

        processor.process(
            geofenceId = "polygon",
            transition = Event.GeofenceTransition.ENTER,
            timestampSeconds = 100L,
            enforceConfiguredTransition = true,
            expectedRegionRevision = current.transitionRevision() + 1
        )

        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { store.commitBusinessTransition(any(), any(), any(), any(), any()) }
    }

    @Test
    fun process_givenDuplicateSuppressedAfterRecovery_expectContainmentStillCommitted() = runTest {
        coEvery { emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.SUPPRESSED

        processor.process("polygon", Event.GeofenceTransition.ENTER, 100L)

        verify {
            store.commitBusinessTransition("polygon", Event.GeofenceTransition.ENTER, null, 0L, any())
        }
    }

    @Test
    fun process_givenUnconfiguredPolygonEnter_expectTracksContainmentWithoutEmitting() = runTest {
        every { store.getCachedRegion("polygon") } returns GeofenceRegion(
            id = "polygon",
            latitude = 0.0,
            longitude = 0.0,
            radius = 100f,
            transitionTypes = listOf(GeofenceTransitionType.EXIT)
        )

        processor.process(
            geofenceId = "polygon",
            transition = Event.GeofenceTransition.ENTER,
            timestampSeconds = 100L,
            enforceConfiguredTransition = true
        )

        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        verify {
            store.commitBusinessTransition("polygon", Event.GeofenceTransition.ENTER, null, 0L, any())
        }
    }

    @Test
    fun process_givenUserSwitchAfterOldCallbackAdmission_expectDoesNotAttributeTransitionToNewUser() = runTest {
        var generation = 1L
        var currentUser = "user-1"
        every { store.userStateGeneration() } answers { generation }
        every { secureUserStore.getUserId() } answers { currentUser }
        every { store.activeUserSessionId() } answers { currentUser }
        every { store.getRoutableRegisteredIds() } answers {
            // The old callback already observed its fence as routable. User B identifies before
            // identity and durable staging, which must invalidate this attempt.
            generation = 2L
            currentUser = "user-2"
            setOf("polygon")
        }

        processor.process(
            geofenceId = "polygon",
            transition = Event.GeofenceTransition.ENTER,
            timestampSeconds = 100L,
            expectedUserStateGeneration = 1L,
            requireRegistered = true
        )

        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { store.commitBusinessTransition(any(), any(), any(), any(), any()) }
    }
}

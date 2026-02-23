package io.customer.messaginginapp.gist.presentation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.messaginginapp.gist.data.sse.InAppSseLogger
import io.customer.messaginginapp.gist.data.sse.SseConnectionManager
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SseLifecycleManagerTest : JUnitTest() {

    private val sseLogger = mockk<InAppSseLogger>(relaxed = true)
    private val inAppMessagingManager = mockk<InAppMessagingManager>(relaxed = true)
    private val sseConnectionManager = mockk<SseConnectionManager>(relaxed = true)
    private val processLifecycleOwner = mockk<LifecycleOwner>(relaxed = true)
    private val lifecycle = mockk<Lifecycle>(relaxed = true)
    private val mainThreadPoster = mockk<MainThreadPoster>(relaxed = true)
    private val gistQueue = mockk<GistQueue>(relaxed = true)

    private val stateFlow = MutableStateFlow<InAppMessagingState>(
        InAppMessagingState(sseEnabled = false)
    )
    private var sseFlagChangeCallback: ((Boolean) -> Unit)? = null
    private var userIdentificationChangeCallback: ((Boolean) -> Unit)? = null
    private var subscriptionCallCount = 0

    private lateinit var lifecycleManager: SseLifecycleManager

    @BeforeEach
    fun setup() {
        subscriptionCallCount = 0
        sseFlagChangeCallback = null
        userIdentificationChangeCallback = null

        every { processLifecycleOwner.lifecycle } returns lifecycle
        every { lifecycle.currentState } returns Lifecycle.State.CREATED
        every { lifecycle.addObserver(any()) } just Runs

        // Mock mainThreadPoster to execute immediately (synchronously) for tests
        val postSlot = slot<() -> Unit>()
        every { mainThreadPoster.post(capture(postSlot)) } answers {
            postSlot.captured.invoke()
        }

        // Mock subscribeToAttribute to capture callbacks for both sseEnabled and isUserIdentified
        // First subscription is for sseEnabled, second is for isUserIdentified
        every {
            inAppMessagingManager.subscribeToAttribute<Boolean>(
                any(),
                any(),
                any()
            )
        } answers {
            val callback = thirdArg<(Boolean) -> Unit>()
            val job = mockk<Job>(relaxed = true)

            if (subscriptionCallCount == 0) {
                // First subscription is for sseEnabled
                sseFlagChangeCallback = callback
                callback(stateFlow.value.sseEnabled)
            } else {
                // Second subscription is for isUserIdentified
                userIdentificationChangeCallback = callback
                callback(stateFlow.value.isUserIdentified)
            }
            subscriptionCallCount++

            job
        }

        every { inAppMessagingManager.getCurrentState() } answers { stateFlow.value }
    }

    @Test
    fun testInit_whenAppIsCreated_thenObserverIsRegistered() {
        // Given
        every { lifecycle.currentState } returns Lifecycle.State.CREATED

        // When
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        // Then
        verify { lifecycle.addObserver(any()) }
        verify(exactly = 0) { sseConnectionManager.startConnection() }
    }

    @Test
    fun testInit_whenAppIsStarted_thenHandlesForegrounded() {
        // Given
        every { lifecycle.currentState } returns Lifecycle.State.STARTED
        stateFlow.value = InAppMessagingState(sseEnabled = true, userId = "user-123")

        // When
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        // Then
        verify { lifecycle.addObserver(any()) }
        verify { sseConnectionManager.startConnection() }
    }

    @Test
    fun testOnStart_whenSseEnabledAndUserIdentified_thenStartsConnection() {
        // Given - SSE enabled and user is identified
        stateFlow.value = InAppMessagingState(sseEnabled = true, userId = "user-123")
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        val observerSlot = slot<androidx.lifecycle.LifecycleObserver>()
        verify { lifecycle.addObserver(capture(observerSlot)) }

        // When
        val observer = observerSlot.captured as androidx.lifecycle.DefaultLifecycleObserver
        observer.onStart(processLifecycleOwner)

        // Then
        verify { sseConnectionManager.startConnection() }
    }

    @Test
    fun testOnStart_whenSseDisabled_thenDoesNotStartConnection() {
        // Given
        stateFlow.value = InAppMessagingState(sseEnabled = false)
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        val observerSlot = slot<androidx.lifecycle.LifecycleObserver>()
        verify { lifecycle.addObserver(capture(observerSlot)) }

        // When
        val observer = observerSlot.captured as androidx.lifecycle.DefaultLifecycleObserver
        observer.onStart(processLifecycleOwner)

        // Then
        verify(exactly = 0) { sseConnectionManager.startConnection() }
    }

    @Test
    fun testOnStart_whenAlreadyForegrounded_thenSkips() {
        // Given - SSE enabled and user is identified
        stateFlow.value = InAppMessagingState(sseEnabled = true, userId = "user-123")
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        val observerSlot = slot<androidx.lifecycle.LifecycleObserver>()
        verify { lifecycle.addObserver(capture(observerSlot)) }
        val observer = observerSlot.captured as androidx.lifecycle.DefaultLifecycleObserver

        // First call
        observer.onStart(processLifecycleOwner)
        verify(exactly = 1) { sseConnectionManager.startConnection() }

        // When - second call
        observer.onStart(processLifecycleOwner)

        // Then - should not call again
        verify(exactly = 1) { sseConnectionManager.startConnection() }
    }

    @Test
    fun testOnStop_thenStopsConnection() {
        // Given
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        val observerSlot = slot<androidx.lifecycle.LifecycleObserver>()
        verify { lifecycle.addObserver(capture(observerSlot)) }
        val observer = observerSlot.captured as androidx.lifecycle.DefaultLifecycleObserver

        // First foreground the app
        observer.onStart(processLifecycleOwner)

        // When - background the app
        observer.onStop(processLifecycleOwner)

        // Then
        verify { sseConnectionManager.stopConnection() }
    }

    @Test
    fun testOnStop_whenAlreadyBackgrounded_thenSkips() {
        // Given
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        val observerSlot = slot<androidx.lifecycle.LifecycleObserver>()
        verify { lifecycle.addObserver(capture(observerSlot)) }
        val observer = observerSlot.captured as androidx.lifecycle.DefaultLifecycleObserver

        // First foreground the app
        observer.onStart(processLifecycleOwner)

        // First call - background the app
        observer.onStop(processLifecycleOwner)
        verify(exactly = 1) { sseConnectionManager.stopConnection() }

        // When - second call (already backgrounded)
        observer.onStop(processLifecycleOwner)

        // Then - should not call again
        verify(exactly = 1) { sseConnectionManager.stopConnection() }
    }

    @Test
    fun testSseFlagChange_whenForegroundedAndEnabledAndUserIdentified_thenStartsConnection() {
        // Given - User is identified
        stateFlow.value = InAppMessagingState(sseEnabled = false, userId = "user-123")
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        // Simulate foreground
        val observerSlot = slot<androidx.lifecycle.LifecycleObserver>()
        verify { lifecycle.addObserver(capture(observerSlot)) }
        val observer = observerSlot.captured as androidx.lifecycle.DefaultLifecycleObserver
        observer.onStart(processLifecycleOwner)

        // When - SSE flag changes to true
        stateFlow.value = InAppMessagingState(sseEnabled = true, userId = "user-123")
        sseFlagChangeCallback?.invoke(true)

        // Then
        verify { sseConnectionManager.startConnection() }
    }

    @Test
    fun testSseFlagChange_whenBackgrounded_thenIgnoresChange() {
        // Given
        stateFlow.value = InAppMessagingState(sseEnabled = false)
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        // App is backgrounded (no onStart called)

        // When - SSE flag changes to true while backgrounded
        stateFlow.value = InAppMessagingState(sseEnabled = true)
        sseFlagChangeCallback?.invoke(true)

        // Then - should not start connection
        verify(exactly = 0) { sseConnectionManager.startConnection() }
    }

    @Test
    fun testSseFlagChange_whenForegroundedAndDisabled_thenStopsConnection() {
        // Given - SSE enabled and user is identified
        stateFlow.value = InAppMessagingState(sseEnabled = true, userId = "user-123")
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        // Simulate foreground
        val observerSlot = slot<androidx.lifecycle.LifecycleObserver>()
        verify { lifecycle.addObserver(capture(observerSlot)) }
        val observer = observerSlot.captured as androidx.lifecycle.DefaultLifecycleObserver
        observer.onStart(processLifecycleOwner)

        // When - SSE flag changes to false
        stateFlow.value = InAppMessagingState(sseEnabled = false, userId = "user-123")
        sseFlagChangeCallback?.invoke(false)

        // Then
        verify { sseConnectionManager.stopConnection() }
    }

    @Test
    fun testReset_whenForegroundedAndSseEnabledAndUserIdentified_thenRestartsConnection() {
        // Given - SSE enabled and user is identified
        stateFlow.value = InAppMessagingState(sseEnabled = true, userId = "user-123")
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        // Simulate foreground
        val observerSlot = slot<androidx.lifecycle.LifecycleObserver>()
        verify { lifecycle.addObserver(capture(observerSlot)) }
        val observer = observerSlot.captured as androidx.lifecycle.DefaultLifecycleObserver
        observer.onStart(processLifecycleOwner)
        verify(exactly = 1) { sseConnectionManager.startConnection() }

        // When - reset while app is still foregrounded
        lifecycleManager.reset()

        // Then - should stop and restart connection
        verify(exactly = 2) { sseConnectionManager.startConnection() }
    }

    @Test
    fun testReset_whenForegroundedButSseDisabled_thenOnlyStopsConnection() {
        // Given
        stateFlow.value = InAppMessagingState(sseEnabled = false)
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        // Simulate foreground
        val observerSlot = slot<androidx.lifecycle.LifecycleObserver>()
        verify { lifecycle.addObserver(capture(observerSlot)) }
        val observer = observerSlot.captured as androidx.lifecycle.DefaultLifecycleObserver
        observer.onStart(processLifecycleOwner)

        // When - reset while app is still foregrounded but SSE disabled
        lifecycleManager.reset()

        // Then - should only stop connection, not restart
        verify(exactly = 0) { sseConnectionManager.startConnection() }
    }

    // =====================
    // Anonymous vs Identified User Tests
    // =====================

    @Test
    fun testOnStart_whenSseEnabledButUserAnonymous_thenDoesNotStartConnection() {
        // Given - SSE enabled but user is anonymous (no userId, only anonymousId)
        stateFlow.value = InAppMessagingState(
            sseEnabled = true,
            userId = null,
            anonymousId = "anonymous-123"
        )
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        val observerSlot = slot<androidx.lifecycle.LifecycleObserver>()
        verify { lifecycle.addObserver(capture(observerSlot)) }

        // When
        val observer = observerSlot.captured as androidx.lifecycle.DefaultLifecycleObserver
        observer.onStart(processLifecycleOwner)

        // Then - should NOT start SSE for anonymous users
        verify(exactly = 0) { sseConnectionManager.startConnection() }
    }

    @Test
    fun testSseFlagChange_whenForegroundedAndUserAnonymous_thenDoesNotStartConnection() {
        // Given - User is anonymous
        stateFlow.value = InAppMessagingState(
            sseEnabled = false,
            userId = null,
            anonymousId = "anonymous-123"
        )
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        // Simulate foreground
        val observerSlot = slot<androidx.lifecycle.LifecycleObserver>()
        verify { lifecycle.addObserver(capture(observerSlot)) }
        val observer = observerSlot.captured as androidx.lifecycle.DefaultLifecycleObserver
        observer.onStart(processLifecycleOwner)

        // When - SSE flag changes to true but user is still anonymous
        stateFlow.value = InAppMessagingState(
            sseEnabled = true,
            userId = null,
            anonymousId = "anonymous-123"
        )
        sseFlagChangeCallback?.invoke(true)

        // Then - should NOT start connection for anonymous users
        verify(exactly = 0) { sseConnectionManager.startConnection() }
    }

    @Test
    fun testReset_whenForegroundedAndSseEnabledButAnonymous_thenDoesNotRestartConnection() {
        // Given - SSE enabled but user is anonymous
        stateFlow.value = InAppMessagingState(
            sseEnabled = true,
            userId = null,
            anonymousId = "anonymous-123"
        )
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        // Simulate foreground
        val observerSlot = slot<androidx.lifecycle.LifecycleObserver>()
        verify { lifecycle.addObserver(capture(observerSlot)) }
        val observer = observerSlot.captured as androidx.lifecycle.DefaultLifecycleObserver
        observer.onStart(processLifecycleOwner)

        // When - reset while app is still foregrounded
        lifecycleManager.reset()

        // Then - should NOT restart connection for anonymous users
        verify(exactly = 0) { sseConnectionManager.startConnection() }
    }

    @Test
    fun testUserBecomesIdentified_whenForegroundedAndSseEnabled_thenStartsConnection() {
        // Given - User starts as anonymous, SSE is enabled
        stateFlow.value = InAppMessagingState(
            sseEnabled = true,
            userId = null,
            anonymousId = "anonymous-123"
        )
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        // Simulate foreground
        val observerSlot = slot<androidx.lifecycle.LifecycleObserver>()
        verify { lifecycle.addObserver(capture(observerSlot)) }
        val observer = observerSlot.captured as androidx.lifecycle.DefaultLifecycleObserver
        observer.onStart(processLifecycleOwner)

        // SSE should NOT have started (user is anonymous)
        verify(exactly = 0) { sseConnectionManager.startConnection() }

        // When - User becomes identified
        stateFlow.value = InAppMessagingState(
            sseEnabled = true,
            userId = "user-123",
            anonymousId = "anonymous-123"
        )
        userIdentificationChangeCallback?.invoke(true)

        // Then - SSE should now start
        verify(exactly = 1) { sseConnectionManager.startConnection() }
    }

    @Test
    fun testUserBecomesAnonymous_whenForegroundedAndSseEnabled_thenStopsConnection() {
        // Given - User starts as identified, SSE is enabled
        stateFlow.value = InAppMessagingState(
            sseEnabled = true,
            userId = "user-123",
            anonymousId = "anonymous-123"
        )
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        // Simulate foreground
        val observerSlot = slot<androidx.lifecycle.LifecycleObserver>()
        verify { lifecycle.addObserver(capture(observerSlot)) }
        val observer = observerSlot.captured as androidx.lifecycle.DefaultLifecycleObserver
        observer.onStart(processLifecycleOwner)

        // SSE should have started (user is identified)
        verify(exactly = 1) { sseConnectionManager.startConnection() }

        // When - User becomes anonymous
        stateFlow.value = InAppMessagingState(
            sseEnabled = true,
            userId = null,
            anonymousId = "anonymous-123"
        )
        userIdentificationChangeCallback?.invoke(false)

        // Then - SSE should be stopped
        verify(exactly = 1) { sseConnectionManager.stopConnection() }
    }

    @Test
    fun testUserBecomesIdentified_whenBackgrounded_thenDoesNotStartConnection() {
        // Given - User starts as anonymous, SSE is enabled, app is backgrounded
        stateFlow.value = InAppMessagingState(
            sseEnabled = true,
            userId = null,
            anonymousId = "anonymous-123"
        )
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        // App is NOT foregrounded (no onStart called)

        // When - User becomes identified while backgrounded
        stateFlow.value = InAppMessagingState(
            sseEnabled = true,
            userId = "user-123",
            anonymousId = "anonymous-123"
        )
        userIdentificationChangeCallback?.invoke(true)

        // Then - SSE should NOT start (app is backgrounded)
        verify(exactly = 0) { sseConnectionManager.startConnection() }
    }

    @Test
    fun testUserBecomesIdentified_whenSseDisabled_thenDoesNotStartConnection() {
        // Given - User starts as anonymous, SSE is disabled
        stateFlow.value = InAppMessagingState(
            sseEnabled = false,
            userId = null,
            anonymousId = "anonymous-123"
        )
        lifecycleManager = SseLifecycleManager(
            inAppMessagingManager = inAppMessagingManager,
            processLifecycleOwner = processLifecycleOwner,
            sseConnectionManager = sseConnectionManager,
            sseLogger = sseLogger,
            gistQueue = gistQueue,
            mainThreadPoster = mainThreadPoster
        )

        // Simulate foreground
        val observerSlot = slot<androidx.lifecycle.LifecycleObserver>()
        verify { lifecycle.addObserver(capture(observerSlot)) }
        val observer = observerSlot.captured as androidx.lifecycle.DefaultLifecycleObserver
        observer.onStart(processLifecycleOwner)

        // When - User becomes identified but SSE is disabled
        stateFlow.value = InAppMessagingState(
            sseEnabled = false,
            userId = "user-123",
            anonymousId = "anonymous-123"
        )
        userIdentificationChangeCallback?.invoke(true)

        // Then - SSE should NOT start (SSE flag is disabled)
        verify(exactly = 0) { sseConnectionManager.startConnection() }
    }
}

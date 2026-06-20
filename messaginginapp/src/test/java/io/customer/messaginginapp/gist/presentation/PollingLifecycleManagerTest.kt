package io.customer.messaginginapp.gist.presentation

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.MainThreadPoster
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PollingLifecycleManagerTest : JUnitTest() {

    private val inAppMessagingManager = mockk<InAppMessagingManager>(relaxed = true)
    private val processLifecycleOwner = mockk<LifecycleOwner>(relaxed = true)
    private val lifecycle = mockk<Lifecycle>(relaxed = true)
    private val gistQueue = mockk<GistQueue>(relaxed = true)
    private val logger = mockk<Logger>(relaxed = true)
    private val mainThreadPoster = mockk<MainThreadPoster>(relaxed = true)

    // SSE inactive by default (sseEnabled=false), so polling is the active transport.
    private var state = InAppMessagingState(pollInterval = 600000L)

    // Captured attribute-subscription listeners, in registration order:
    // 0 = pollInterval, 1 = sseEnabled, 2 = isUserIdentified. They are captured (not auto-invoked)
    // so tests can drive specific transitions deterministically.
    private val subscriptionListeners = mutableListOf<(Any?) -> Unit>()
    private val pollIntervalListener get() = subscriptionListeners[0]
    private val sseEnabledListener get() = subscriptionListeners[1]
    private val userIdentifiedListener get() = subscriptionListeners[2]

    @BeforeEach
    fun setupManager() {
        subscriptionListeners.clear()
        every { processLifecycleOwner.lifecycle } returns lifecycle
        every { lifecycle.currentState } returns Lifecycle.State.CREATED
        every { lifecycle.addObserver(any()) } just Runs
        every { inAppMessagingManager.getCurrentState() } answers { state }

        // Capture each attribute listener without invoking it (the real impl replays the current
        // value asynchronously; capturing lets tests trigger genuine transitions on demand).
        @Suppress("UNCHECKED_CAST")
        every { inAppMessagingManager.subscribeToAttribute<Any>(any(), any(), any()) } answers {
            subscriptionListeners.add(thirdArg<(Any?) -> Unit>())
            mockk(relaxed = true)
        }

        // Run the lifecycle-registration block synchronously so construction is deterministic.
        val postSlot = slot<() -> Unit>()
        every { mainThreadPoster.post(capture(postSlot)) } answers { postSlot.captured.invoke() }
    }

    private fun createManager(): PollingLifecycleManager = PollingLifecycleManager(
        inAppMessagingManager = inAppMessagingManager,
        processLifecycleOwner = processLifecycleOwner,
        gistQueue = gistQueue,
        logger = logger,
        mainThreadPoster = mainThreadPoster
    )

    private fun captureObserver(): DefaultLifecycleObserver {
        val observerSlot = slot<LifecycleObserver>()
        verify { lifecycle.addObserver(capture(observerSlot)) }
        return observerSlot.captured as DefaultLifecycleObserver
    }

    @Test
    fun init_whenAppCreated_registersObserverAndDoesNotPoll() {
        every { lifecycle.currentState } returns Lifecycle.State.CREATED

        createManager()

        verify { lifecycle.addObserver(any()) }
        verify(exactly = 0) { gistQueue.fetchUserMessages() }
    }

    @Test
    fun init_whenAppStartedAndSseInactive_startsPollingAndFetches() {
        every { lifecycle.currentState } returns Lifecycle.State.STARTED
        state = InAppMessagingState(pollInterval = 600000L, sseEnabled = false)

        createManager()

        verify(timeout = 2000) { gistQueue.fetchUserMessages() }
    }

    @Test
    fun onStart_whenSseInactive_startsPollingAndFetches() {
        createManager()
        val observer = captureObserver()

        observer.onStart(processLifecycleOwner)

        verify(timeout = 2000) { gistQueue.fetchUserMessages() }
    }

    @Test
    fun onStart_whenSseActive_doesNotPoll() {
        // SSE active: enabled flag + identified user.
        state = InAppMessagingState(pollInterval = 600000L, sseEnabled = true, userId = "user-123")
        createManager()
        val observer = captureObserver()

        observer.onStart(processLifecycleOwner)

        verify(exactly = 0) { gistQueue.fetchUserMessages() }
        verify { logger.debug(match { it.contains("Not starting polling") }) }
    }

    @Test
    fun onStart_whenAlreadyForegrounded_skipsSecondTime() {
        createManager()
        val observer = captureObserver()

        observer.onStart(processLifecycleOwner)
        observer.onStart(processLifecycleOwner)

        verify { logger.debug(match { it.contains("already foregrounded") }) }
    }

    @Test
    fun onStop_afterForeground_stopsPolling() {
        createManager()
        val observer = captureObserver()

        observer.onStart(processLifecycleOwner)
        observer.onStop(processLifecycleOwner)

        verify { logger.debug(match { it.contains("App backgrounded - stopping polling") }) }
    }

    @Test
    fun fetchInAppMessages_whenSseInactive_fetches() {
        val manager = createManager()

        manager.fetchInAppMessages()

        verify(timeout = 2000) { gistQueue.fetchUserMessages() }
    }

    @Test
    fun fetchInAppMessages_whenSseActive_doesNotFetch() {
        state = InAppMessagingState(pollInterval = 600000L, sseEnabled = true, userId = "user-123")
        val manager = createManager()

        manager.fetchInAppMessages()

        verify(exactly = 0) { gistQueue.fetchUserMessages() }
    }

    @Test
    fun pollIntervalSubscription_initialEmissionIsSkipped_butGenuineChangeRestarts() {
        createManager()
        val observer = captureObserver()
        observer.onStart(processLifecycleOwner) // foreground -> polling started

        // Initial replay emission of the current interval must not restart polling (would race
        // with / cancel the foreground catch-up fetch).
        pollIntervalListener(600000L)
        verify(exactly = 0) { logger.debug(match { it.contains("Poll interval changed") }) }

        // A genuine interval change restarts polling.
        state = state.copy(pollInterval = 10000L)
        pollIntervalListener(10000L)
        verify { logger.debug(match { it.contains("Poll interval changed to 10000ms") }) }
    }

    @Test
    fun sseAvailabilityChange_whenPollingAlreadyRunning_doesNotRestart() {
        createManager()
        val observer = captureObserver()
        observer.onStart(processLifecycleOwner) // foreground -> polling running, SSE inactive

        // An identification change that leaves shouldUseSse=false (e.g. anon->identified with SSE
        // off) must not restart polling - ModuleMessagingInApp owns that catch-up fetch.
        state = state.copy(userId = "user-123") // sseEnabled still false -> shouldUseSse false
        userIdentifiedListener(true)

        verify { logger.debug(match { it.contains("polling already running, no action") }) }
        verify(exactly = 0) { logger.debug(match { it.contains("starting polling") }) }
    }

    @Test
    fun sseAvailabilityChange_whenPollingStopped_resumesPolling() {
        // Start with SSE active so foregrounding does not start polling (timer stays null).
        state = InAppMessagingState(pollInterval = 600000L, sseEnabled = true, userId = "user-123")
        createManager()
        val observer = captureObserver()
        observer.onStart(processLifecycleOwner)
        verify(exactly = 0) { gistQueue.fetchUserMessages() }

        // SSE flag flips off while foregrounded+identified -> shouldUseSse false, timer null -> resume.
        state = state.copy(sseEnabled = false)
        sseEnabledListener(false)

        verify(timeout = 2000) { gistQueue.fetchUserMessages() }
    }
}

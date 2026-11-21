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
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
        verify(exactly = 0) { gistQueue.fetchUserMessages(any()) }
    }

    @Test
    fun init_whenAppStartedAndSseInactive_startsPollingAndFetches() {
        every { lifecycle.currentState } returns Lifecycle.State.STARTED
        state = InAppMessagingState(pollInterval = 600000L, sseEnabled = false)

        createManager()

        verify(timeout = 2000) { gistQueue.fetchUserMessages(any()) }
    }

    @Test
    fun onStart_whenSseInactive_startsPollingAndFetches() {
        createManager()
        val observer = captureObserver()

        observer.onStart(processLifecycleOwner)

        verify(timeout = 2000) { gistQueue.fetchUserMessages(any()) }
    }

    @Test
    fun onStart_givenFirstForegroundSinceLaunch_expectCatchUpMayShareInFlightFetch() {
        createManager()
        val observer = captureObserver()

        observer.onStart(processLifecycleOwner)

        verify(timeout = 2000) { gistQueue.fetchUserMessages(mustBeFresh = false) }
        verify(exactly = 0) { gistQueue.fetchUserMessages(mustBeFresh = true) }
    }

    @Test
    fun onStart_givenReturningFromBackground_expectCatchUpNeedsFreshData() {
        createManager()
        val observer = captureObserver()
        observer.onStart(processLifecycleOwner)
        verify(timeout = 2000) { gistQueue.fetchUserMessages(mustBeFresh = false) }

        observer.onStop(processLifecycleOwner)
        observer.onStart(processLifecycleOwner)

        verify(timeout = 2000) { gistQueue.fetchUserMessages(mustBeFresh = true) }
    }

    @Test
    fun onStart_whenSseActive_doesNotPoll() {
        // SSE active: enabled flag + identified user.
        state = InAppMessagingState(pollInterval = 600000L, sseEnabled = true, userId = "user-123")
        createManager()
        val observer = captureObserver()

        observer.onStart(processLifecycleOwner)

        verify(exactly = 0) { gistQueue.fetchUserMessages(any()) }
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
        bringToForegroundAndSettle()

        manager.fetchInAppMessages()

        verify(timeout = 2000) { gistQueue.fetchUserMessages(any()) }
    }

    @Test
    fun fetchInAppMessages_whenSseActive_doesNotFetch() {
        state = InAppMessagingState(pollInterval = 600000L, sseEnabled = true, userId = "user-123")
        val manager = createManager()

        manager.fetchInAppMessages()

        verify(exactly = 0) { gistQueue.fetchUserMessages(any()) }
    }

    @Test
    fun fetchInAppMessages_givenPendingFreshPoll_expectReplacementFetchIsFreshOnlyOnce() {
        val manager = createManager()
        try {
            bringToForegroundAndSettle()
            startDelayedFreshPoll(manager)

            manager.fetchInAppMessages()
            verify(timeout = 2000, exactly = 1) { gistQueue.fetchUserMessages(mustBeFresh = true) }

            manager.fetchInAppMessages()
            verify(timeout = 2000, exactly = 1) { gistQueue.fetchUserMessages(mustBeFresh = false) }
            verify(exactly = 1) { gistQueue.fetchUserMessages(mustBeFresh = true) }
        } finally {
            manager.reset()
        }
    }

    @Test
    fun reset_givenPendingFreshPoll_expectRequirementCleared() {
        val manager = createManager()
        try {
            bringToForegroundAndSettle()
            startDelayedFreshPoll(manager)

            manager.reset()
            manager.fetchInAppMessages()

            verify(timeout = 2000) { gistQueue.fetchUserMessages(mustBeFresh = false) }
            verify(exactly = 0) { gistQueue.fetchUserMessages(mustBeFresh = true) }
        } finally {
            manager.reset()
        }
    }

    // Brings the manager to the foreground and waits for its launch fetch, so tests of later fetches
    // start from a foregrounded manager with no recorded fetches.
    private fun bringToForegroundAndSettle() {
        captureObserver().onStart(processLifecycleOwner)
        verify(timeout = 2000) { gistQueue.fetchUserMessages(any()) }
        clearMocks(gistQueue, answers = false)
    }

    // Delay the foreground timer so replacement always happens before its first tick.
    private fun startDelayedFreshPoll(manager: PollingLifecycleManager) {
        val startPolling = PollingLifecycleManager::class.java.getDeclaredMethod(
            "startPolling",
            Long::class.java,
            Long::class.java,
            Boolean::class.java
        )
        startPolling.isAccessible = true
        startPolling.invoke(manager, 600000L, 60000L, true)
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
    fun startPolling_underConcurrentCalls_leavesNoOrphanTimerAfterReset() {
        // Regression for the orphaned-timer leak: concurrent startPolling calls (main-thread
        // foreground + subscription coroutines + dismiss/identify fetch) must not leave any
        // Timer alive after reset(). A fast interval surfaces orphans quickly.
        val fetchCount = AtomicInteger(0)
        every { gistQueue.fetchUserMessages(any()) } answers { fetchCount.incrementAndGet() }
        state = InAppMessagingState(pollInterval = 30L)
        val manager = createManager()
        // Fetches are skipped while the app is in the background, so start from the foreground.
        bringToForegroundAndSettle()
        fetchCount.set(0)

        val threads = (1..8).map { Thread { repeat(25) { manager.fetchInAppMessages() } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        // Guards against a vacuous pass: the concurrent calls must have reached the queue.
        assertTrue(fetchCount.get() > 0, "No fetch happened, the orphan-timer check below would pass without testing anything")

        // Stop polling; after a settle window no further ticks should occur if exactly one timer
        // existed (without the fix, orphaned timers keep firing every 30ms).
        manager.reset()
        Thread.sleep(100) // let any in-flight tick + cancellation settle
        val countAfterReset = fetchCount.get()
        Thread.sleep(300) // ~10 periods would elapse if an orphan timer were still alive
        assertEquals(countAfterReset, fetchCount.get(), "Polling continued after reset - orphaned timer leak")
    }

    @Test
    fun sseAvailabilityChange_whenPollingStopped_resumesPolling() {
        // Start with SSE active so foregrounding does not start polling (timer stays null).
        state = InAppMessagingState(pollInterval = 600000L, sseEnabled = true, userId = "user-123")
        createManager()
        val observer = captureObserver()
        observer.onStart(processLifecycleOwner)
        verify(exactly = 0) { gistQueue.fetchUserMessages(any()) }

        // SSE flag flips off while foregrounded+identified -> shouldUseSse false, timer null -> resume.
        state = state.copy(sseEnabled = false)
        sseEnabledListener(false)

        verify(timeout = 2000) { gistQueue.fetchUserMessages(any()) }
    }

    // --- MBL-2808: background-start control. A process that starts with no UI (push, worker,
    // service) never reaches STARTED, so polling must not start from SDK init or identify. ---

    /**
     * R1. SDK init, identify and reset all reach [PollingLifecycleManager.fetchInAppMessages]
     * through the UserChangedEvent handler. With the process never started there is no UI to show a
     * message on, so no queue fetch may happen.
     *
     * Absence is observed over a bounded window. The timer's initialDelay is 0, so when polling does
     * start a tick lands within milliseconds; the mutation check (remove the foreground guard,
     * expect this test to fail) is what proves the window is long enough.
     */
    @Test
    fun fetchInAppMessages_givenProcessNeverStarted_expectNoQueueFetch() {
        every { lifecycle.currentState } returns Lifecycle.State.CREATED
        val fetched = CountDownLatch(1)
        every { gistQueue.fetchUserMessages(any()) } answers { fetched.countDown() }
        val manager = createManager()

        manager.fetchInAppMessages()

        assertFalse(
            fetched.await(1, TimeUnit.SECONDS),
            "Queue was fetched while the process had no started activity"
        )
    }

    /**
     * P3 (catch-up preservation). A process that started with no UI and later gets its first
     * foreground must fetch the queue then, so messages that could not be shown earlier appear.
     * Before the foreground gate this also passes through the background fetch, so it only becomes
     * a strict check once the gate removes that fetch.
     */
    @Test
    fun onStart_givenProcessStartedWithoutUi_expectFirstForegroundFetchesQueue() {
        every { lifecycle.currentState } returns Lifecycle.State.CREATED
        val manager = createManager()
        val observer = captureObserver()
        manager.fetchInAppMessages()

        observer.onStart(processLifecycleOwner)

        verify(timeout = 2000) { gistQueue.fetchUserMessages(any()) }
    }

    /**
     * R2. The isUserIdentified subscription is already gated on the foreground flag, so identifying
     * a user while backgrounded starts nothing. Guards that the fix for R1 does not regress it.
     */
    @Test
    fun userIdentified_givenProcessNeverStarted_expectNoQueueFetch() {
        every { lifecycle.currentState } returns Lifecycle.State.CREATED
        val fetched = CountDownLatch(1)
        every { gistQueue.fetchUserMessages(any()) } answers { fetched.countDown() }
        state = InAppMessagingState(pollInterval = 600000L, sseEnabled = false, userId = "user-123")
        createManager()

        userIdentifiedListener(true)

        assertFalse(
            fetched.await(1, TimeUnit.SECONDS),
            "Queue was fetched on identification while the process had no started activity"
        )
    }
}

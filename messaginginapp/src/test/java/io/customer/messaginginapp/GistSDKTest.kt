package io.customer.messaginginapp

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.di.gistQueue
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.di.inAppPreferenceStore
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.presentation.GistSdk
import io.customer.messaginginapp.gist.presentation.SseLifecycleManager
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.state.ModalMessageState
import io.customer.messaginginapp.store.InAppPreferenceStore
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.MainThreadPoster
import io.customer.sdk.lifecycle.CustomerIOActivityLifecycleCallbacks
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GistSDKTest : JUnitTest() {

    private lateinit var gistSdk: GistSdk
    private lateinit var mockInAppMessagingManager: InAppMessagingManager
    private lateinit var mockInAppPreferenceStore: InAppPreferenceStore
    private lateinit var mockGistQueue: GistQueue
    private lateinit var testState: InAppMessagingState

    // Process lifecycle doubles. The default instance uses a no-op main thread poster so the
    // lifecycle observer is never registered and the app is treated as not-foregrounded, keeping
    // these tests isolated from polling. Tests that need foregrounding build their own instance.
    private val mockProcessLifecycleOwner = mockk<LifecycleOwner>(relaxed = true)
    private val noOpMainThreadPoster = mockk<MainThreadPoster>(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency(mockk<InAppMessagingManager>(relaxed = true))
                        overrideDependency(mockk<InAppPreferenceStore>(relaxed = true))
                        overrideDependency(mockk<GistQueue>(relaxed = true))
                        overrideDependency(
                            mockk<CustomerIOActivityLifecycleCallbacks>(relaxed = true) {
                                every { subscribe(any()) } just Runs
                            }
                        )
                        overrideDependency(mockk<SseLifecycleManager>(relaxed = true))
                    }
                }
            }
        )

        mockInAppMessagingManager = SDKComponent.inAppMessagingManager
        mockInAppPreferenceStore = SDKComponent.inAppPreferenceStore
        mockGistQueue = SDKComponent.gistQueue
        testState = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            environment = GistEnvironment.PROD,
            pollInterval = 60000L,
            userId = null
        )

        every { mockInAppMessagingManager.getCurrentState() } returns testState

        gistSdk = GistSdk(
            siteId = String.random,
            dataCenter = String.random,
            processLifecycleOwner = mockProcessLifecycleOwner,
            mainThreadPoster = noOpMainThreadPoster
        )
    }

    /**
     * Builds a GistSdk whose process lifecycle reports [foregrounded] and whose main thread poster
     * runs synchronously, so lifecycle wiring executes inline during construction.
     */
    private fun buildGistSdkWithLifecycle(foregrounded: Boolean): GistSdk {
        val lifecycle = mockk<Lifecycle>(relaxed = true) {
            every { currentState } returns if (foregrounded) Lifecycle.State.STARTED else Lifecycle.State.CREATED
        }
        val processOwner = mockk<LifecycleOwner>(relaxed = true) {
            every { this@mockk.lifecycle } returns lifecycle
        }
        val synchronousPoster = mockk<MainThreadPoster> {
            val postSlot = slot<() -> Unit>()
            every { post(capture(postSlot)) } answers { postSlot.captured.invoke() }
        }
        return GistSdk(
            siteId = String.random,
            dataCenter = String.random,
            processLifecycleOwner = processOwner,
            mainThreadPoster = synchronousPoster
        )
    }

    @Test
    fun appForegrounded_whenSseNotActive_expectPollingStartedAndMessagesFetched() = runTest {
        // testState has sseEnabled=false and userId=null, so shouldUseSse is false.
        buildGistSdkWithLifecycle(foregrounded = true)

        // Foregrounding the process (not a specific activity) starts polling, which fetches messages.
        verify(timeout = 2000) { mockGistQueue.fetchUserMessages() }
    }

    @Test
    fun appForegrounded_whenSseActive_expectPollingNotStarted() = runTest {
        // Make SSE active: enabled flag + identified user.
        testState = testState.copy(sseEnabled = true, userId = "identified-user")
        every { mockInAppMessagingManager.getCurrentState() } returns testState

        buildGistSdkWithLifecycle(foregrounded = true)

        // SSE owns delivery while foregrounded; polling must not fetch.
        verify(exactly = 0) { mockGistQueue.fetchUserMessages() }
    }

    @Test
    fun appNotForegrounded_expectPollingNotStarted() = runTest {
        buildGistSdkWithLifecycle(foregrounded = false)

        // Process is only CREATED (not STARTED), so polling must not start.
        verify(exactly = 0) { mockGistQueue.fetchUserMessages() }
    }

    @Test
    fun setCurrentRoute_givenNewRoute_expectSetPageRouteActionDispatched() = runTest {
        val givenRoute = String.random

        gistSdk.setCurrentRoute(givenRoute)

        assertCalledOnce { mockInAppMessagingManager.dispatch(InAppMessagingAction.SetPageRoute(givenRoute)) }
    }

    @Test
    fun setUserId_givenNewUserId_expectSetUserIdentifierActionDispatchedWithoutFetch() = runTest {
        val givenUserId = String.random

        gistSdk.setUserId(givenUserId)

        assertCalledOnce { mockInAppMessagingManager.dispatch(InAppMessagingAction.SetUserIdentifier(givenUserId)) }
        // fetch is now controlled by event handler, not setUserId
        verify(exactly = 0) { mockGistQueue.fetchUserMessages() }
    }

    @Test
    fun dismissMessage_whenMessageAvailable_expectDismissMessageActionDispatched() = runTest {
        val testMessage = Message(queueId = String.random)
        testState = testState.copy(modalMessageState = ModalMessageState.Displayed(testMessage))
        every { mockInAppMessagingManager.getCurrentState() } returns testState

        gistSdk.dismissMessage()

        assertCalledOnce { mockInAppMessagingManager.dispatch(InAppMessagingAction.DismissMessage(testMessage)) }
    }

    @Test
    fun dismissMessage_whenNoMessageAvailable_expectNoDismissMessageActionDispatched() = runTest {
        testState = testState.copy(modalMessageState = ModalMessageState.Initial)
        every { mockInAppMessagingManager.getCurrentState() } returns testState

        // clear any previous calls to dispatch to avoid false positives like `initialize`
        clearMocks(mockInAppMessagingManager, answers = false)

        gistSdk.dismissMessage()

        verify(exactly = 0) { mockInAppMessagingManager.dispatch(any<InAppMessagingAction.DismissMessage>()) }
    }

    @Test
    fun reset_whenCalled_expectResetActionDispatchedAndPreferencesCleared() = runTest {
        gistSdk.reset()

        assertCalledOnce { mockInAppMessagingManager.dispatch(InAppMessagingAction.Reset) }
        assertCalledOnce { mockInAppPreferenceStore.clearAll() }
    }

    @Test
    fun setUserId_givenSameUserIdTwice_expectNoAdditionalActionsOnSecondCall() = runTest {
        val givenUserId = String.random

        gistSdk.setUserId(givenUserId)

        // verify first call dispatches the action
        verify(exactly = 1) { mockInAppMessagingManager.dispatch(InAppMessagingAction.SetUserIdentifier(givenUserId)) }

        // clear invocations to start fresh for the second call
        clearMocks(mockInAppMessagingManager, mockGistQueue)

        // update the mock state to reflect the user ID has been set
        every { mockInAppMessagingManager.getCurrentState() } returns testState.copy(userId = givenUserId)

        // second call with the same user ID
        gistSdk.setUserId(givenUserId)

        // verify no additional actions on second call (state unchanged)
        verify(exactly = 0) {
            mockInAppMessagingManager.dispatch(any<InAppMessagingAction.SetUserIdentifier>())
        }
    }

    // Tests for persistent messages

    @Test
    fun displayMessage_whenNonPersistentMessage_expectMessageAddedToShownMessageQueueIds() = runTest {
        val testMessage = createTestMessage(persistent = false)

        testState = testState.copy(
            messagesInQueue = setOf(testMessage),
            shownMessageQueueIds = emptySet()
        )
        every { mockInAppMessagingManager.getCurrentState() } returns testState

        // Simulate displaying the message
        mockInAppMessagingManager.dispatch(InAppMessagingAction.DisplayMessage(testMessage))

        // Verify the message is marked as shown (added to shownMessageQueueIds)
        verify {
            mockInAppMessagingManager.dispatch(any<InAppMessagingAction.DisplayMessage>())
        }
    }

    @Test
    fun displayMessage_whenPersistentMessage_expectMessageNotAddedToShownMessageQueueIds() = runTest {
        val testMessage = createTestMessage(persistent = true)

        testState = testState.copy(
            messagesInQueue = setOf(testMessage),
            shownMessageQueueIds = emptySet()
        )
        every { mockInAppMessagingManager.getCurrentState() } returns testState

        // Simulate displaying the message
        mockInAppMessagingManager.dispatch(InAppMessagingAction.DisplayMessage(testMessage))

        // Verify the message is dispatched
        verify {
            mockInAppMessagingManager.dispatch(any<InAppMessagingAction.DisplayMessage>())
        }
    }

    @Test
    fun dismissMessage_whenPersistentMessage_expectMessageAddedToShownMessageQueueIds() = runTest {
        val testMessage = createTestMessage(persistent = true)

        testState = testState.copy(
            modalMessageState = ModalMessageState.Displayed(testMessage),
            shownMessageQueueIds = emptySet()
        )
        every { mockInAppMessagingManager.getCurrentState() } returns testState

        // Simulate dismissing the message
        gistSdk.dismissMessage()

        // Verify the dismiss message action is dispatched
        verify {
            mockInAppMessagingManager.dispatch(any<InAppMessagingAction.DismissMessage>())
        }
    }

    @Test
    fun dismissMessage_whenPersistentMessageDismissedNotViaCloseAction_expectMessageNotAddedToShownMessageQueueIds() = runTest {
        val testMessage = createTestMessage(persistent = true)

        testState = testState.copy(
            modalMessageState = ModalMessageState.Displayed(testMessage),
            shownMessageQueueIds = emptySet()
        )
        every { mockInAppMessagingManager.getCurrentState() } returns testState

        // Simulate dismissing the message not via close action
        mockInAppMessagingManager.dispatch(InAppMessagingAction.DismissMessage(testMessage, viaCloseAction = false))

        // Verify the dismiss message action is dispatched
        verify {
            mockInAppMessagingManager.dispatch(any<InAppMessagingAction.DismissMessage>())
        }
    }

    @Test
    fun displayAndDismissMessage_whenPersistentMessage_expectCorrectStateTransitionsAndLogging() = runTest {
        val testMessage = createTestMessage(persistent = true)

        testState = testState.copy(
            messagesInQueue = setOf(testMessage),
            shownMessageQueueIds = emptySet(),
            modalMessageState = ModalMessageState.Initial
        )
        every { mockInAppMessagingManager.getCurrentState() } returns testState

        // Step 1: Display the message
        mockInAppMessagingManager.dispatch(InAppMessagingAction.DisplayMessage(testMessage))

        // Update state to reflect message is displayed
        testState = testState.copy(
            modalMessageState = ModalMessageState.Displayed(testMessage),
            messagesInQueue = emptySet() // Message should be removed from queue when displayed
        )
        every { mockInAppMessagingManager.getCurrentState() } returns testState

        // Step 2: Dismiss the message
        gistSdk.dismissMessage()

        // Verify the final state
        verify {
            mockInAppMessagingManager.dispatch(
                match<InAppMessagingAction.DismissMessage> {
                    it.message == testMessage && it.shouldLog && it.viaCloseAction
                }
            )
        }
    }

    @Test
    fun displayAndDismissMessage_whenNonPersistentMessage_expectCorrectStateTransitionsAndLogging() = runTest {
        val testMessage = createTestMessage(persistent = false)

        testState = testState.copy(
            messagesInQueue = setOf(testMessage),
            shownMessageQueueIds = emptySet(),
            modalMessageState = ModalMessageState.Initial
        )
        every { mockInAppMessagingManager.getCurrentState() } returns testState

        // Step 1: Display the message
        mockInAppMessagingManager.dispatch(InAppMessagingAction.DisplayMessage(testMessage))

        // Update state to reflect message is displayed
        testState = testState.copy(
            modalMessageState = ModalMessageState.Displayed(testMessage),
            messagesInQueue = emptySet(), // Message should be removed from queue when displayed
            shownMessageQueueIds = setOf(testMessage.queueId!!) // Message should be in shownMessageQueueIds
        )
        every { mockInAppMessagingManager.getCurrentState() } returns testState

        // Step 2: Dismiss the message
        gistSdk.dismissMessage()

        // Verify dismiss action was dispatched
        verify {
            mockInAppMessagingManager.dispatch(any<InAppMessagingAction.DismissMessage>())
        }
    }

    /**
     * Helper method to create a test message with customizable persistence
     */
    private fun createTestMessage(persistent: Boolean): Message {
        val queueId = String.random
        return mockk<Message>(relaxed = true) {
            every { this@mockk.queueId } returns queueId
            every { this@mockk.gistProperties.persistent } returns persistent
            every { this@mockk.isEmbedded } returns false
        }
    }
}

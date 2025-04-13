package io.customer.messaginginapp

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
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.state.MessageState
import io.customer.messaginginapp.store.InAppPreferenceStore
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.sdk.core.di.SDKComponent
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
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

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency(mockk<InAppMessagingManager>(relaxed = true))
                        overrideDependency(mockk<InAppPreferenceStore>(relaxed = true))
                        overrideDependency(mockk<GistQueue>(relaxed = true))
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

        gistSdk = GistSdk(siteId = String.random, dataCenter = String.random)
    }

    @Test
    fun setCurrentRoute_givenNewRoute_expectSetPageRouteActionDispatched() = runTest {
        val givenRoute = String.random

        gistSdk.setCurrentRoute(givenRoute)

        assertCalledOnce { mockInAppMessagingManager.dispatch(InAppMessagingAction.SetPageRoute(givenRoute)) }
    }

    @Test
    fun setUserId_givenNewUserId_expectSetUserIdentifierActionDispatchedAndMessagesFetched() = runTest {
        val givenUserId = String.random

        gistSdk.setUserId(givenUserId)

        assertCalledOnce { mockInAppMessagingManager.dispatch(InAppMessagingAction.SetUserIdentifier(givenUserId)) }
        assertCalledOnce { mockGistQueue.fetchUserMessages() }
    }

    @Test
    fun dismissMessage_whenMessageAvailable_expectDismissMessageActionDispatched() = runTest {
        val testMessage = Message(queueId = String.random)
        testState = testState.copy(modalMessageState = MessageState.Displayed(testMessage))
        every { mockInAppMessagingManager.getCurrentState() } returns testState

        gistSdk.dismissMessage()

        assertCalledOnce { mockInAppMessagingManager.dispatch(InAppMessagingAction.DismissMessage(testMessage)) }
    }

    @Test
    fun dismissMessage_whenNoMessageAvailable_expectNoDismissMessageActionDispatched() = runTest {
        testState = testState.copy(modalMessageState = MessageState.Initial)
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

        // verify first call actions
        verify(exactly = 1) { mockInAppMessagingManager.dispatch(InAppMessagingAction.SetUserIdentifier(givenUserId)) }
        verify(exactly = 1) { mockGistQueue.fetchUserMessages() }

        // clear invocations to start fresh for the second call
        clearMocks(mockInAppMessagingManager, mockGistQueue)

        // update the mock state to reflect the user ID has been set
        every { mockInAppMessagingManager.getCurrentState() } returns testState.copy(userId = givenUserId)

        // second call with the same user ID
        gistSdk.setUserId(givenUserId)

        // verify no additional actions on second call
        verify(exactly = 0) {
            mockInAppMessagingManager.dispatch(any<InAppMessagingAction.SetUserIdentifier>())
        }
        verify(exactly = 0) {
            mockGistQueue.fetchUserMessages()
        }
    }
}

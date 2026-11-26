package io.customer.messaginginapp.gist.presentation.hostwindow

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.random
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.ModalMessageState
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.messaginginapp.testutils.core.foregroundedProcessConfig
import io.customer.messaginginapp.testutils.extension.createInAppMessage
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InAppMessagingManagerModalAdaptersTest : IntegrationTest() {

    private val inAppEventListener = mockk<InAppEventListener>(relaxed = true)

    // Store subscriptions collect on this scope. Running it after each step delivers every state.
    private val scopeProviderStub = ScopeProviderStub.Standard()

    private lateinit var manager: InAppMessagingManager
    private lateinit var stateSource: InAppMessagingManagerModalStateSource
    private lateinit var dismisser: InAppMessagingManagerModalDismisser

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfig + foregroundedProcessConfig() + testConfigurationDefault {
                diGraph { sdk { overrideDependency<ScopeProvider>(scopeProviderStub) } }
            }
        )
        ModuleMessagingInApp(
            config = MessagingInAppModuleConfig.Builder(siteId = TestConstants.Keys.SITE_ID)
                .setEventListener(inAppEventListener)
                .build()
        ).attachToSDKComponent()
        manager = SDKComponent.inAppMessagingManager
        manager.dispatch(InAppMessagingAction.Initialize(siteId = String.random, dataCenter = String.random, environment = GistEnvironment.PROD))
        manager.dispatch(InAppMessagingAction.SetUserIdentifier(String.random))
        stateSource = InAppMessagingManagerModalStateSource(manager)
        dismisser = InAppMessagingManagerModalDismisser(manager)
    }

    override fun teardown() {
        manager.dispatch(InAppMessagingAction.Reset)
        super.teardown()
    }

    private fun dispatch(action: InAppMessagingAction) {
        manager.dispatch(action)
        scopeProviderStub.inAppLifecycleScope.testScheduler.runCurrent()
    }

    private fun load(message: Message) = dispatch(InAppMessagingAction.LoadMessage(message))

    private fun display(message: Message) = dispatch(InAppMessagingAction.DisplayMessage(message))

    private fun verifyDismissedEvents(message: Message, times: Int) = verify(exactly = times) {
        inAppEventListener.messageDismissed(match { it.queueId == message.queueId })
    }

    @Test
    fun stateSource_givenStoreMovesThroughModalLifecycle_expectEachStateMapped() {
        val message = createInAppMessage(queueId = "1")
        val next = createInAppMessage(queueId = "2")
        val emitted = mutableListOf<ModalPresentationState>()
        stateSource.subscribe { emitted.add(it) }
        scopeProviderStub.inAppLifecycleScope.testScheduler.runCurrent()

        load(message)
        stateSource.currentState shouldBeEqualTo ModalPresentationState.Loading(message)
        display(message)
        stateSource.currentState shouldBeEqualTo ModalPresentationState.Displayed(message)
        dispatch(InAppMessagingAction.DismissMessage(message))
        stateSource.currentState shouldBeEqualTo ModalPresentationState.Idle
        load(next)
        dispatch(InAppMessagingAction.Reset)
        stateSource.currentState shouldBeEqualTo ModalPresentationState.Idle

        emitted shouldBeEqualTo listOf(
            ModalPresentationState.Idle,
            ModalPresentationState.Loading(message),
            ModalPresentationState.Displayed(message),
            ModalPresentationState.Idle,
            ModalPresentationState.Loading(next),
            ModalPresentationState.Idle
        )
    }

    @Test
    fun stateSource_givenUnsubscribed_expectNoFurtherUpdates() {
        val emitted = mutableListOf<ModalPresentationState>()
        val unsubscribe = stateSource.subscribe { emitted.add(it) }
        scopeProviderStub.inAppLifecycleScope.testScheduler.runCurrent()

        unsubscribe()
        load(createInAppMessage(queueId = "1"))

        emitted shouldBeEqualTo listOf(ModalPresentationState.Idle)
    }

    @Test
    fun dismiss_givenMessageDisplayed_expectMessageDismissedOnce() {
        val message = createInAppMessage(queueId = "1")
        load(message)
        display(message)

        dismisser.dismiss(message)

        manager.getCurrentState().modalMessageState shouldBeEqualTo ModalMessageState.Dismissed(message)
        verifyDismissedEvents(message, times = 1)
    }

    @Test
    fun dismiss_givenMessageStillLoading_expectStoreUnchanged() {
        val message = createInAppMessage(queueId = "1")
        load(message)

        dismisser.dismiss(message)

        manager.getCurrentState().modalMessageState shouldBeEqualTo ModalMessageState.Loading(message)
        verifyDismissedEvents(message, times = 0)
    }

    @Test
    fun dismiss_givenStoreMovedToNextMessage_expectNextMessageLeftLoading() {
        val message = createInAppMessage(queueId = "1")
        val next = createInAppMessage(queueId = "2")
        load(message)
        display(message)
        dispatch(InAppMessagingAction.DismissMessage(message))
        load(next)

        dismisser.dismiss(message)

        manager.getCurrentState().modalMessageState shouldBeEqualTo ModalMessageState.Loading(next)
        verifyDismissedEvents(message, times = 1)
    }

    @Test
    fun dismiss_givenMessageAlreadyDismissed_expectNoSecondDismissal() {
        val message = createInAppMessage(queueId = "1")
        load(message)
        display(message)
        dismisser.dismiss(message)

        dismisser.dismiss(message)

        manager.getCurrentState().modalMessageState.shouldBeInstanceOf<ModalMessageState.Dismissed>()
        verifyDismissedEvents(message, times = 1)
    }
}

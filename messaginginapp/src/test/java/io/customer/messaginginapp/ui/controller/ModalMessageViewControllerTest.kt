package io.customer.messaginginapp.ui.controller

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.assertNoInteractions
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.messaginginapp.testutils.extension.createInAppMessage
import io.customer.messaginginapp.ui.bridge.EngineWebViewDelegate
import io.customer.messaginginapp.ui.bridge.InAppHostViewDelegate
import io.customer.messaginginapp.ui.bridge.InAppPlatformDelegate
import io.customer.messaginginapp.ui.bridge.ModalInAppMessageViewCallback
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifyOrder
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test

class ModalMessageViewControllerTest : JUnitTest() {
    private val engineWebViewDelegate = mockk<EngineWebViewDelegate>(relaxed = true)
    private val inAppMessagingManager: InAppMessagingManager = mockk(relaxed = true)
    private val viewDelegate: InAppHostViewDelegate = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency(inAppMessagingManager)
                    }
                }
            }
        )

        every { viewDelegate.createEngineWebViewInstance() } returns engineWebViewDelegate
    }

    private fun createViewController(): ModalInAppMessageViewController {
        val platformDelegate: InAppPlatformDelegate = mockk(relaxed = true)
        val instance = ModalInAppMessageViewController(
            platformDelegate = platformDelegate,
            viewDelegate = viewDelegate
        )
        return spyk(instance)
    }

    @Test
    fun init_givenControllerInitialized_expectEngineAttached() {
        val controller = createViewController()

        controller.engineWebViewDelegate.shouldNotBeNull()
    }

    @Test
    fun engineCleanup_givenEngineDetached_expectCallbackCleared() {
        val controller = createViewController()
        controller.viewCallback = mockk<ModalInAppMessageViewCallback>(relaxed = true)

        controller.detachEngineWebView()

        controller.viewCallback.shouldBeNull()
    }

    @Test
    fun engineCleanup_givenAlreadyDetached_expectNoOp() {
        val controller = createViewController()
        controller.detachEngineWebView()
        controller.viewCallback = mockk<ModalInAppMessageViewCallback>(relaxed = true)

        controller.detachEngineWebView()

        controller.viewCallback.shouldNotBeNull()
    }

    @Test
    fun routeLoaded_givenDisplayEventPending_expectDispatchTriggered() {
        val controller = createViewController()
        val givenMessage = createInAppMessage()
        val givenRoute = String.random
        controller.currentMessage = givenMessage

        controller.routeLoaded(givenRoute)

        controller.currentRoute shouldBeEqualTo givenRoute
        verifyOrder {
            engineWebViewDelegate.setAlpha(1.0F)
            inAppMessagingManager.dispatch(
                InAppMessagingAction.DisplayMessage(givenMessage)
            )
        }
    }

    @Test
    fun routeLoaded_givenDisplayEventAlreadyDispatched_expectNoDispatch() {
        val controller = createViewController()
        val givenMessage = createInAppMessage()
        val givenRoute = String.random
        controller.currentMessage = givenMessage
        controller.routeLoaded(givenRoute)
        clearMocks(engineWebViewDelegate, inAppMessagingManager)

        controller.routeLoaded(givenRoute)

        controller.currentRoute shouldBeEqualTo givenRoute
        assertNoInteractions(engineWebViewDelegate, inAppMessagingManager)
    }

    @Test
    fun sizeChanged_givenHeightStaysCollapsedAfterDisplay_expectMessageFailed() {
        val controller = createViewController()
        val givenMessage = createInAppMessage()
        controller.currentMessage = givenMessage
        controller.routeLoaded(String.random)

        // A collapsed modal covers the screen and swallows touches without showing anything.
        repeat(ModalSizePolicy.SAMPLE_COUNT) { controller.sizeChanged(width = 320.0, height = 0.0) }

        assertCalledOnce {
            inAppMessagingManager.dispatch(
                InAppMessagingAction.EngineAction.MessageLoadingFailed(givenMessage)
            )
        }
    }

    @Test
    fun sizeChanged_givenHeightStaysCollapsedAfterDisplay_expectMessageFailedOnlyOnce() {
        val controller = createViewController()
        val givenMessage = createInAppMessage()
        controller.currentMessage = givenMessage
        controller.routeLoaded(String.random)

        repeat(ModalSizePolicy.SAMPLE_COUNT * 3) {
            controller.sizeChanged(width = 320.0, height = 0.0)
        }

        assertCalledOnce {
            inAppMessagingManager.dispatch(
                InAppMessagingAction.EngineAction.MessageLoadingFailed(givenMessage)
            )
        }
    }

    @Test
    fun sizeChanged_givenCollapsedHeightBeforeDisplay_expectNoFailure() {
        val controller = createViewController()
        controller.currentMessage = createInAppMessage()

        // While the message loads the WebView is detached and legitimately measures zero.
        repeat(ModalSizePolicy.SAMPLE_COUNT * 2) {
            controller.sizeChanged(width = 0.0, height = 0.0)
        }

        verify(exactly = 0) {
            inAppMessagingManager.dispatch(
                ofType<InAppMessagingAction.EngineAction.MessageLoadingFailed>()
            )
        }
    }

    @Test
    fun sizeChanged_givenResolvedHeightAfterDisplay_expectSizeForwardedAndNoFailure() {
        val controller = createViewController()
        val viewCallback = mockk<ModalInAppMessageViewCallback>(relaxed = true)
        controller.currentMessage = createInAppMessage()
        controller.viewCallback = viewCallback
        controller.routeLoaded(String.random)

        repeat(ModalSizePolicy.SAMPLE_COUNT) {
            controller.sizeChanged(width = 320.0, height = 382.0)
        }

        verify(exactly = ModalSizePolicy.SAMPLE_COUNT) {
            viewCallback.onViewSizeChanged(any(), any())
        }
        verify(exactly = 0) {
            inAppMessagingManager.dispatch(
                ofType<InAppMessagingAction.EngineAction.MessageLoadingFailed>()
            )
        }
    }

    @Test
    fun sizeChanged_givenHeightGrowsByConstantAfterDisplay_expectSizeStillAppliedAndNoFailure() {
        val controller = createViewController()
        val viewCallback = mockk<ModalInAppMessageViewCallback>(relaxed = true)
        controller.currentMessage = createInAppMessage()
        controller.viewCallback = viewCallback
        controller.routeLoaded(String.random)

        // Viewport dependent height: the reported value tracks the WebView height. The message
        // still renders (clamped by the container), so it must not be failed.
        listOf(704.0, 736.0, 768.0, 800.0).forEach {
            controller.sizeChanged(width = 320.0, height = it)
        }

        verify(exactly = 4) { viewCallback.onViewSizeChanged(any(), any()) }
        verify(exactly = 0) {
            inAppMessagingManager.dispatch(
                ofType<InAppMessagingAction.EngineAction.MessageLoadingFailed>()
            )
        }
    }

    @Test
    fun bootstrapped_givenValidMessageId_expectNoOp() {
        val controller = createViewController()
        val givenMessage = createInAppMessage()
        controller.currentMessage = givenMessage

        controller.bootstrapped()

        controller.engineWebViewDelegate.shouldNotBeNull()
        controller.currentMessage.shouldNotBeNull()
    }

    @Test
    fun bootstrapped_givenEmptyMessageId_expectEngineCleanupTriggered() {
        val controller = createViewController()
        val givenMessage = createInAppMessage(messageId = "")
        controller.currentMessage = givenMessage

        controller.bootstrapped()

        assertCalledOnce { controller.detachEngineWebView() }
        controller.engineWebViewDelegate.shouldBeNull()
        controller.currentMessage.shouldBeNull()
    }
}

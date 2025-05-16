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
import io.mockk.verifyOrder
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
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

        controller.shouldDispatchDisplayEvent.shouldBeFalse()
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
        controller.shouldDispatchDisplayEvent = false
        clearMocks(engineWebViewDelegate)

        controller.routeLoaded(givenRoute)

        controller.shouldDispatchDisplayEvent.shouldBeFalse()
        controller.currentRoute shouldBeEqualTo givenRoute
        assertNoInteractions(engineWebViewDelegate, inAppMessagingManager)
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

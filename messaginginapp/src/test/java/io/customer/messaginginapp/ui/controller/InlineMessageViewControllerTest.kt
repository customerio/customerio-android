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
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Test

class InlineMessageViewControllerTest : JUnitTest() {
    private val engineWebViewDelegate: EngineWebViewDelegate = mockk(relaxed = true)
    private val inAppMessagingManager: InAppMessagingManager = mockk(relaxed = true)
    private val platformDelegate: InAppPlatformDelegate = mockk(relaxed = true)
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
        every { viewDelegate.post(any()) } answers {
            firstArg<() -> Unit>().invoke()
        }
    }

    private fun createViewController(): InlineInAppMessageViewController {
        val instance = InlineInAppMessageViewController(
            platformDelegate = platformDelegate,
            viewDelegate = viewDelegate
        )
        return spyk(instance)
    }

    @Test
    fun init_givenControllerInitialized_expectViewSetupCorrectly() {
        val controller = createViewController()

        controller.engineWebViewDelegate.shouldBeNull()
        assertCalledOnce {
            viewDelegate.isVisible = false
            inAppMessagingManager.subscribeToState(any(), any())
        }
    }

    @Test
    fun onDetached_givenActivityFinishing_expectDismissEventDispatched() {
        val controller = createViewController()
        val givenMessage = createInAppMessage()
        controller.currentMessage = givenMessage
        every { platformDelegate.shouldDestroyViewOnDetach() } returns true

        controller.onDetachedFromWindow()

        assertCalledOnce {
            inAppMessagingManager.dispatch(
                InAppMessagingAction.DismissMessage(
                    message = givenMessage,
                    shouldLog = false,
                    viaCloseAction = false
                )
            )
        }
    }

    @Test
    fun onDetached_givenActivityChangingConfiguration_expectNoEventDispatched() {
        val controller = createViewController()
        val givenMessage = createInAppMessage()
        controller.currentMessage = givenMessage
        every { platformDelegate.shouldDestroyViewOnDetach() } returns false
        clearMocks(inAppMessagingManager, engineWebViewDelegate, viewDelegate)

        controller.onDetachedFromWindow()

        assertNoInteractions(inAppMessagingManager, engineWebViewDelegate, viewDelegate)
    }

    @Test
    fun onDetached_givenNoMessageSet_expectNoEventDispatched() {
        val controller = createViewController()
        every { platformDelegate.shouldDestroyViewOnDetach() } returns false
        clearMocks(inAppMessagingManager, engineWebViewDelegate, viewDelegate, platformDelegate)

        controller.onDetachedFromWindow()

        assertNoInteractions(
            inAppMessagingManager,
            engineWebViewDelegate,
            viewDelegate,
            platformDelegate
        )
    }

    @Test
    fun routeLoaded_givenDisplayEventPending_expectDispatchTriggered() {
        val controller = createViewController()
        val givenMessage = createInAppMessage()
        val givenRoute = String.random
        controller.currentMessage = givenMessage

        controller.routeLoaded(givenRoute)

        controller.currentRoute shouldBeEqualTo givenRoute
        assertCalledOnce {
            inAppMessagingManager.dispatch(InAppMessagingAction.DisplayMessage(givenMessage))
        }
    }
}

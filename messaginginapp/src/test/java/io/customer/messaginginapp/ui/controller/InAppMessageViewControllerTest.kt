package io.customer.messaginginapp.ui.controller

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.assertNoInteractions
import io.customer.commontest.extensions.filterNotNullValues
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.di.gistCustomAttributes
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.engine.EngineWebConfiguration
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.messaginginapp.testutils.extension.createGistAction
import io.customer.messaginginapp.testutils.extension.createInAppMessage
import io.customer.messaginginapp.testutils.extension.mapToInAppMessage
import io.customer.messaginginapp.testutils.extension.setMessageAndRouteForTest
import io.customer.messaginginapp.testutils.fakes.FakeQuerySanitizer
import io.customer.messaginginapp.type.InlineMessageActionListener
import io.customer.messaginginapp.ui.bridge.EngineWebViewDelegate
import io.customer.messaginginapp.ui.bridge.InAppHostViewDelegate
import io.customer.messaginginapp.ui.bridge.InAppMessageViewCallback
import io.customer.messaginginapp.ui.bridge.InAppPlatformDelegate
import io.customer.sdk.core.di.SDKComponent
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifyOrder
import java.net.URI
import kotlin.math.roundToInt
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test

class InAppMessageViewControllerTest : JUnitTest() {
    //region Setup test environment
    private val inAppMessagingManager: InAppMessagingManager = mockk(relaxed = true)
    private val platformDelegate: InAppPlatformDelegate = mockk(relaxed = true)
    private val viewDelegate: InAppHostViewDelegate = mockk(relaxed = true)
    private lateinit var controller: InAppMessageViewController<InAppMessageViewCallback>

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

        every { platformDelegate.parseJavaURI(any()) } answers { URI(firstArg<String>()) }

        controller = spyk(object : InAppMessageViewController<InAppMessageViewCallback>(
            type = "Test",
            platformDelegate = platformDelegate,
            viewDelegate = viewDelegate
        ) {})
    }

    //endregion
    //region Tap interaction tests

    @Test
    fun tap_givenActionAndNoCurrentMessage_expectNoMessageManagerInteraction() {
        controller.currentRoute = String.random

        controller.tap(
            name = String.random,
            action = String.random,
            system = true
        )

        assertNoInteractions(inAppMessagingManager)
    }

    @Test
    fun tap_givenActionAndNoCurrentRoute_expectNoMessageManagerInteraction() {
        controller.currentMessage = createInAppMessage()

        controller.tap(
            name = String.random,
            action = String.random,
            system = true
        )

        assertNoInteractions(inAppMessagingManager)
    }

    @Test
    fun tap_givenAnyAction_expectDispatchTapActionToManager() {
        val givenMessage = createInAppMessage()
        val givenAction = String.random
        val givenName = String.random
        val givenRoute = String.random
        controller.setMessageAndRouteForTest(
            message = givenMessage,
            route = givenRoute
        )

        controller.tap(
            name = givenName,
            action = givenAction,
            system = true
        )

        verify {
            inAppMessagingManager.dispatch(
                InAppMessagingAction.EngineAction.Tap(
                    message = givenMessage,
                    route = givenRoute,
                    name = givenName,
                    action = givenAction
                )
            )
        }
    }

    @Test
    fun tap_givenActionWithActionListener_expectDispatchToGlobalAndLocal() {
        val actionListenerMock = mockk<InlineMessageActionListener>()
        val givenMessage = createInAppMessage()
        val givenAction = String.random
        val givenName = String.random
        val givenRoute = String.random
        controller.setMessageAndRouteForTest(
            message = givenMessage,
            route = givenRoute
        )
        controller.actionListener = actionListenerMock

        controller.tap(
            name = givenName,
            action = givenAction,
            system = true
        )

        val expectedInAppMessage = mapToInAppMessage(givenMessage)
        verifyOrder {
            inAppMessagingManager.dispatch(
                InAppMessagingAction.EngineAction.Tap(
                    message = givenMessage,
                    route = givenRoute,
                    name = givenName,
                    action = givenAction
                )
            )
            actionListenerMock.onActionClick(
                message = expectedInAppMessage,
                actionValue = givenAction,
                actionName = givenName
            )
        }
    }

    @Test
    fun tap_givenTapHandlingThrows_expectNoCrash() {
        val givenMessage = createInAppMessage()
        val givenAction = "gist://close"
        val givenName = String.random
        val givenRoute = String.random
        controller.setMessageAndRouteForTest(
            message = givenMessage,
            route = givenRoute
        )
        every {
            platformDelegate.parseJavaURI(any())
        } throws RuntimeException("Crash on parseJavaURI")

        controller.tap(
            name = givenName,
            action = givenAction,
            system = true
        )

        // If no exception is thrown, the test passes
        // No further assertions are needed
    }

    @Test
    fun tap_givenCloseAction_expectCloseActionDispatched() {
        val givenMessage = createInAppMessage()
        val givenAction = createGistAction("close")
        val givenName = String.random
        val givenRoute = String.random
        controller.setMessageAndRouteForTest(
            message = givenMessage,
            route = givenRoute
        )

        controller.tap(
            name = givenName,
            action = givenAction,
            system = true
        )

        verify {
            inAppMessagingManager.dispatch(
                InAppMessagingAction.DismissMessage(
                    message = givenMessage,
                    viaCloseAction = true
                )
            )
        }
    }

    @Test
    fun tap_givenLoadPageAction_expectOpenUrlDispatched() {
        val givenMessage = createInAppMessage()
        val givenAction = createGistAction("loadPage")
        val givenName = String.random
        val givenRoute = String.random
        every { platformDelegate.sanitizeUrlQuery(any()) } answers {
            FakeQuerySanitizer(
                mapOf("url" to "https://example.com?utm_source=test&discount=50")
            )
        }
        controller.setMessageAndRouteForTest(
            message = givenMessage,
            route = givenRoute
        )

        controller.tap(
            name = givenName,
            action = givenAction,
            system = true
        )

        verify { platformDelegate.openUrl(url = any(), useLaunchFlags = false) }
    }

    @Test
    fun tap_givenShowMessageAction_expectDismissCurrentAndShowNewMessage() {
        val givenMessage = createInAppMessage()
        val tapGestureProperties = givenMessage.properties?.filterNotNullValues() ?: emptyMap()
        val givenAction = createGistAction("showMessage")
        val givenName = String.random
        val givenRoute = String.random
        every {
            platformDelegate.parsePropertiesFromJson(any())
        } returns tapGestureProperties
        every {
            platformDelegate.sanitizeUrlQuery(any())
        } answers {
            FakeQuerySanitizer(
                mapOf("messageId" to givenMessage.messageId)
            )
        }
        controller.setMessageAndRouteForTest(
            message = givenMessage,
            route = givenRoute
        )

        controller.tap(
            name = givenName,
            action = givenAction,
            system = true
        )

        verifyOrder {
            inAppMessagingManager.dispatch(
                InAppMessagingAction.DismissMessage(
                    message = givenMessage,
                    shouldLog = false
                )
            )
            inAppMessagingManager.dispatch(
                InAppMessagingAction.LoadMessage(
                    Message(
                        messageId = givenMessage.messageId,
                        properties = tapGestureProperties
                    )
                )
            )
        }
    }

    @Test
    fun tap_givenSystemAction_expectDismissMessageAndOpenSystemIntent() {
        val givenMessage = createInAppMessage()
        val givenAction = "open-link://example.com?utm_source=test&discount=50"
        val givenName = String.random
        val givenRoute = String.random
        controller.setMessageAndRouteForTest(
            message = givenMessage,
            route = givenRoute
        )

        controller.tap(
            name = givenName,
            action = givenAction,
            system = true
        )

        verifyOrder {
            platformDelegate.openUrl(url = givenAction, useLaunchFlags = true)
            inAppMessagingManager.dispatch(
                InAppMessagingAction.DismissMessage(
                    message = givenMessage,
                    shouldLog = false
                )
            )
        }
    }

    //endregion
    //region Route tests

    @Test
    fun routeLoaded_givenPendingEvent_expectRouteUpdatedAndEventDispatched() {
        val givenMessage = createInAppMessage()
        val givenRoute = String.random
        controller.currentMessage = givenMessage

        controller.routeLoaded(givenRoute)

        controller.currentRoute shouldBeEqualTo givenRoute
        verifyOrder {
            inAppMessagingManager.dispatch(
                InAppMessagingAction.DisplayMessage(givenMessage)
            )
        }
    }

    @Test
    fun routeLoaded_givenEventAlreadyDispatched_expectRouteUpdatedAndNoDispatch() {
        val givenMessage = createInAppMessage()
        val givenRoute = String.random
        controller.currentMessage = givenMessage
        controller.routeLoaded(givenRoute)
        clearMocks(inAppMessagingManager)

        controller.routeLoaded(givenRoute)

        controller.currentRoute shouldBeEqualTo givenRoute
        assertNoInteractions(inAppMessagingManager)
    }

    @Test
    fun routeListener_givenRouteError_expectDispatchMessageFailedEvent() {
        val givenMessage = createInAppMessage()
        controller.setMessageAndRouteForTest(message = givenMessage, route = String.random)

        controller.routeError("Error loading message")

        verifyOrder {
            inAppMessagingManager.dispatch(
                InAppMessagingAction.EngineAction.MessageLoadingFailed(givenMessage)
            )
        }
    }

    @Test
    fun routeListener_givenRouteErrorAndNoCurrentMessage_expectNoEventDispatched() {
        controller.routeError("Error loading message")

        assertNoInteractions(inAppMessagingManager)
    }

    @Test
    fun routeListener_givenRouteChanged_expectNoEventDispatched() {
        controller.routeChanged(String.random)

        assertNoInteractions(inAppMessagingManager)
    }

    //endregion
    //region Engine view tests

    @Test
    fun engineSetup_givenEngineAttached_expectConfigured() {
        val engineWebViewDelegate = mockk<EngineWebViewDelegate>(relaxed = true)
        every { viewDelegate.createEngineWebViewInstance() } returns engineWebViewDelegate

        controller.attachEngineWebView()

        controller.engineWebViewDelegate.shouldNotBeNull()
        assertCalledOnce {
            viewDelegate.createEngineWebViewInstance()
            engineWebViewDelegate.setAlpha(0.0F)
            engineWebViewDelegate.listener = controller
            viewDelegate.addView(engineWebViewDelegate)
        }
    }

    @Test
    fun engineSetup_givenEngineAlreadyAttached_expectNoReconfiguration() {
        val engineWebViewDelegate = mockk<EngineWebViewDelegate>(relaxed = true)
        controller.engineWebViewDelegate = engineWebViewDelegate

        controller.attachEngineWebView()

        controller.engineWebViewDelegate.shouldNotBeNull()
        assertCalledNever { viewDelegate.createEngineWebViewInstance() }
        assertNoInteractions(engineWebViewDelegate)
    }

    @Test
    fun engineCleanup_givenEngineDetached_expectReleased() {
        val engineWebViewDelegate = mockk<EngineWebViewDelegate>(relaxed = true)
        controller.engineWebViewDelegate = engineWebViewDelegate

        controller.detachEngineWebView()

        controller.engineWebViewDelegate.shouldBeNull()
        assertCalledOnce {
            engineWebViewDelegate.listener = null
            viewDelegate.removeView(engineWebViewDelegate)
        }
    }

    @Test
    fun engineCleanup_givenEngineAlreadyDetached_expectNoActionTaken() {
        controller.detachEngineWebView()

        controller.engineWebViewDelegate.shouldBeNull()
        assertNoInteractions(viewDelegate)
    }

    @Test
    fun engineSetup_givenLoadMessage_expectEngineConfigured() {
        val givenStoreState = InAppMessagingState(
            siteId = String.random,
            dataCenter = String.random,
            environment = GistEnvironment.LOCAL
        )
        every { inAppMessagingManager.getCurrentState() } returns givenStoreState
        val givenMessage = createInAppMessage()
        val engineWebViewDelegate = mockk<EngineWebViewDelegate>(relaxed = true)
        controller.engineWebViewDelegate = engineWebViewDelegate

        controller.loadMessage(givenMessage)

        controller.currentMessage shouldBeEqualTo givenMessage
        val expectedConfig = EngineWebConfiguration(
            siteId = givenStoreState.siteId,
            dataCenter = givenStoreState.dataCenter,
            messageId = givenMessage.messageId,
            instanceId = givenMessage.instanceId,
            endpoint = givenStoreState.environment.getEngineApiUrl(),
            properties = givenMessage.properties,
            customAttributes = SDKComponent.gistCustomAttributes.toMap()
        )
        assertCalledOnce { engineWebViewDelegate.setup(expectedConfig) }
    }

    @Test
    fun engineSetup_givenStopCalled_expectEngineStopsLoading() {
        val engineWebViewDelegate = mockk<EngineWebViewDelegate>(relaxed = true)
        controller.engineWebViewDelegate = engineWebViewDelegate

        controller.stopLoading()

        assertCalledOnce { engineWebViewDelegate.stopLoading() }
    }

    //endregion
    //region Engine callback tests

    @Test
    fun engineListener_givenEngineError_expectDispatchMessageFailedEvent() {
        val givenMessage = createInAppMessage()
        controller.setMessageAndRouteForTest(message = givenMessage, route = String.random)

        controller.error()

        verifyOrder {
            inAppMessagingManager.dispatch(
                InAppMessagingAction.EngineAction.MessageLoadingFailed(givenMessage)
            )
        }
    }

    @Test
    fun engineListener_givenEngineErrorAndNoCurrentMessage_expectNoEventDispatched() {
        controller.error()

        assertNoInteractions(inAppMessagingManager)
    }

    @Test
    fun engineListener_givenEngineBootstrapped_expectNoEventDispatched() {
        controller.bootstrapped()

        assertNoInteractions(inAppMessagingManager)
    }

    @Test
    fun engineListener_givenSizeChanged_expectViewListenerReceivesCorrectSizeUpdates() {
        val screenDensity = Double.random(1.0, 3.0)
        every { platformDelegate.dpToPx(any()) } answers {
            (firstArg<Double>() * screenDensity).roundToInt()
        }
        val givenWidthInDp = Double.random(100.0, 500.0)
        val givenHeightInDp = Double.random(100.0, 500.0)
        val callback = mockk<InAppMessageViewCallback>(relaxed = true)
        controller.viewCallback = callback

        controller.sizeChanged(givenWidthInDp, givenHeightInDp)

        val expectedWidthInPx = (givenWidthInDp * screenDensity).roundToInt()
        val expectedHeightInPx = (givenHeightInDp * screenDensity).roundToInt()
        verify {
            callback.onViewSizeChanged(expectedWidthInPx, expectedHeightInPx)
        }
    }
}

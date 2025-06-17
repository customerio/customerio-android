package io.customer.messaginginapp.ui.controller

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.assertNoInteractions
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.flushCoroutines
import io.customer.commontest.extensions.random
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.engine.EngineWebConfiguration
import io.customer.messaginginapp.gist.presentation.GistProvider
import io.customer.messaginginapp.gist.presentation.GistSdk
import io.customer.messaginginapp.gist.utilities.ElapsedTimer
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.messaginginapp.testutils.extension.createInAppMessage
import io.customer.messaginginapp.ui.bridge.EngineWebViewDelegate
import io.customer.messaginginapp.ui.bridge.InAppHostViewDelegate
import io.customer.messaginginapp.ui.bridge.InAppPlatformDelegate
import io.customer.messaginginapp.ui.bridge.InlineInAppMessageViewCallback
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.data.model.Region
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verifyOrder
import kotlin.math.roundToInt
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test

class InlineMessageViewControllerBehaviorTest : JUnitTest() {
    private val engineWebViewDelegate: EngineWebViewDelegate = mockk(relaxed = true)
    private val platformDelegate: InAppPlatformDelegate = mockk(relaxed = true)
    private val viewDelegate: InAppHostViewDelegate = mockk(relaxed = true)
    private val scopeProviderStub = ScopeProviderStub.Standard()

    private val moduleConfig = MessagingInAppModuleConfig.Builder(
        siteId = TestConstants.Keys.SITE_ID,
        region = Region.US
    ).build()
    private val gistDataCenter = moduleConfig.region.code
    private val gistEnvironment = GistEnvironment.LOCAL
    private val screenDensity = Double.random(1.0, 3.0)

    private lateinit var elapsedTimer: ElapsedTimer
    private lateinit var gistProvider: GistProvider
    private lateinit var messagingManager: InAppMessagingManager

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<GistQueue>(mockk(relaxed = true))
                        overrideDependency<ScopeProvider>(scopeProviderStub)
                    }
                }
            }
        )

        setupMockCalls()
        ModuleMessagingInApp(config = moduleConfig).attachToSDKComponent()
        gistProvider = GistSdk(
            siteId = moduleConfig.siteId,
            dataCenter = gistDataCenter,
            environment = gistEnvironment
        ).also { SDKComponent.overrideDependency<GistProvider>(it) }
        messagingManager = spyk(SDKComponent.inAppMessagingManager)
            .also { SDKComponent.overrideDependency<InAppMessagingManager>(it) }
        elapsedTimer = spyk(ElapsedTimer())
    }

    override fun teardown() {
        gistProvider.reset()
        super.teardown()
    }

    private fun setupMockCalls() {
        every { viewDelegate.createEngineWebViewInstance() } returns engineWebViewDelegate
        every { viewDelegate.post(any()) } answers {
            // Simulate posting to UI thread
            firstArg<() -> Unit>().invoke()
        }
        every { platformDelegate.dpToPx(any()) } answers {
            (firstArg<Double>() * screenDensity).roundToInt()
        }
        every {
            platformDelegate.animateViewSize(
                widthInDp = any(),
                heightInDp = any(),
                duration = any(),
                onStart = any(),
                onEnd = any()
            )
        } answers {
            // Simulate animation by calling the onStart and onEnd callbacks
            val onStart = arg<(() -> Unit)?>(3)
            onStart?.invoke()

            val onEnd = arg<(() -> Unit)?>(4)
            onEnd?.invoke()
        }
    }

    // Helper functions to set up the initial state for tests
    private fun setupGistAndCreateViewController(): InlineInAppMessageViewController {
        setupGistWithUserId()
        return createViewController()
    }

    private fun setupGistWithUserId() {
        val givenUserId = String.random
        gistProvider.setUserId(givenUserId)
    }

    private fun createViewController(): InlineInAppMessageViewController {
        val instance = InlineInAppMessageViewController(
            platformDelegate = platformDelegate,
            viewDelegate = viewDelegate,
            elapsedTimer = elapsedTimer
        )
        return instance
    }

    // Helper function to mock and initialize view callback
    private fun InlineInAppMessageViewController.initMockViewCallback(): InlineInAppMessageViewCallback {
        val callback = mockk<InlineInAppMessageViewCallback>(relaxed = true)
        viewCallback = callback
        return callback
    }

    @Test
    fun handleMessageState_givenInlineMessageReceived_expectLoadStarted() {
        val controller = setupGistAndCreateViewController()
        val viewCallback = controller.initMockViewCallback()
        val givenElementId = "test-element-id"
        controller.elementId = givenElementId
        val givenInAppMessage = createInAppMessage(queueId = "1", elementId = givenElementId)
        flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        messagingManager
            .dispatch(InAppMessagingAction.EmbedMessages(listOf(givenInAppMessage)))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        assertMessageLoadingCalls(
            controller = controller,
            viewCallback = viewCallback,
            expectedMessage = givenInAppMessage
        )
        controller.engineWebViewDelegate.shouldNotBeNull()
        controller.currentMessage shouldBeEqualTo givenInAppMessage
    }

    @Test
    fun handleMessageState_givenInlineMessageDismissed_expectMessageHidden() {
        val controller = setupGistAndCreateViewController()
        val viewCallback = controller.initMockViewCallback()
        val givenElementId = "test-element-id"
        controller.elementId = givenElementId
        val givenInAppMessage = createInAppMessage(queueId = "1", elementId = givenElementId)
        messagingManager
            .dispatch(InAppMessagingAction.EmbedMessages(listOf(givenInAppMessage)))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        messagingManager
            .dispatch(InAppMessagingAction.DismissMessage(givenInAppMessage))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        assertMessageDismissedCalls(
            viewCallback = viewCallback
        )
        controller.viewCallback.shouldNotBeNull()
        controller.engineWebViewDelegate.shouldBeNull()
        controller.currentMessage.shouldBeNull()
        controller.contentWidthInDp.shouldBeNull()
        controller.contentHeightInDp.shouldBeNull()
    }

    @Test
    fun handleMessageState_givenElementIdChanged_expectPreviousDismissedAndNewMessageShown() {
        val controller = setupGistAndCreateViewController()
        val viewCallback = controller.initMockViewCallback()
        val givenOldElementId = "test-element-id-old"
        val givenOldInAppMessage = createInAppMessage(queueId = "1", elementId = givenOldElementId)
        val givenNewElementId = "test-element-id-new"
        val givenNewInAppMessage = createInAppMessage(queueId = "2", elementId = givenNewElementId)
        controller.elementId = givenOldElementId
        messagingManager.dispatch(
            InAppMessagingAction.EmbedMessages(
                listOf(givenOldInAppMessage, givenNewInAppMessage)
            )
        ).flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        controller.elementId = givenNewElementId
        flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        assertMessageDismissedCalls(
            viewCallback = viewCallback
        )
        assertMessageLoadingCalls(
            controller = controller,
            viewCallback = viewCallback,
            expectedMessage = givenNewInAppMessage
        )
        controller.viewCallback.shouldNotBeNull()
        controller.engineWebViewDelegate.shouldNotBeNull()
        controller.currentMessage shouldBeEqualTo givenNewInAppMessage
    }

    @Test
    fun handleMessageState_givenControllerRecreated_expectExistingMessageShown() {
        every { platformDelegate.shouldDestroyViewOnDetach() } returns false
        val givenElementId = "test-element-id"
        val givenInAppMessage = createInAppMessage(queueId = "1", elementId = givenElementId)
        val givenUserId = String.random
        gistProvider.setUserId(givenUserId)
        messagingManager.dispatch(
            InAppMessagingAction.EmbedMessages(listOf(givenInAppMessage))
        ).flushCoroutines(scopeProviderStub.inAppLifecycleScope)
        messagingManager.dispatch(
            InAppMessagingAction.DisplayMessage(givenInAppMessage)
        ).flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        val controller = createViewController()
        val viewCallback = controller.initMockViewCallback()
        controller.elementId = givenElementId
        flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        assertMessageLoadingCalls(
            controller = controller,
            viewCallback = viewCallback,
            expectedMessage = givenInAppMessage
        )
        controller.viewCallback.shouldNotBeNull()
        controller.engineWebViewDelegate.shouldNotBeNull()
        controller.currentMessage shouldBeEqualTo givenInAppMessage
    }

    @Test
    fun handleMessageState_givenMultipleMessageDisplayed_expectCorrectEventsDispatched() {
        val controller = setupGistAndCreateViewController()
        val viewCallback = controller.initMockViewCallback()
        val givenElementId = "test-element-id"
        controller.elementId = givenElementId
        val givenInAppMessageOne = createInAppMessage(queueId = "1", elementId = givenElementId)
        val givenInAppMessageTwo = createInAppMessage(queueId = "2", elementId = givenElementId)
        messagingManager.dispatch(
            InAppMessagingAction.EmbedMessages(listOf(givenInAppMessageOne))
        ).flushCoroutines(scopeProviderStub.inAppLifecycleScope)
        controller.routeLoaded(String.random)
        messagingManager.dispatch(
            InAppMessagingAction.DismissMessage(givenInAppMessageOne)
        ).flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        messagingManager.dispatch(
            InAppMessagingAction.EmbedMessages(listOf(givenInAppMessageTwo))
        ).flushCoroutines(scopeProviderStub.inAppLifecycleScope)
        controller.routeLoaded(String.random)

        assertCalledOnce {
            messagingManager.dispatch(InAppMessagingAction.DisplayMessage(givenInAppMessageOne))
            messagingManager.dispatch(InAppMessagingAction.DisplayMessage(givenInAppMessageTwo))
        }
        assertMessageLoadingCalls(
            controller = controller,
            viewCallback = viewCallback,
            expectedMessage = givenInAppMessageOne
        )
        assertMessageDismissedCalls(
            viewCallback = viewCallback
        )
        assertMessageLoadingCalls(
            controller = controller,
            viewCallback = viewCallback,
            expectedMessage = givenInAppMessageTwo
        )
        controller.currentMessage shouldBeEqualTo givenInAppMessageTwo
    }

    @Test
    fun messageLoaded_givenSizeUpdate_expectViewMadeVisible() {
        val controller = setupGistAndCreateViewController()
        val viewCallback = controller.initMockViewCallback()
        val givenElementId = "test-element-id"
        controller.elementId = givenElementId
        val givenInAppMessage = createInAppMessage(queueId = "1", elementId = givenElementId)
        messagingManager.dispatch(
            InAppMessagingAction.EmbedMessages(listOf(givenInAppMessage))
        ).flushCoroutines(scopeProviderStub.inAppLifecycleScope)
        val givenWidthInDp = Double.random(100.0, 500.0)
        val givenHeightInDp = Double.random(100.0, 500.0)

        controller.sizeChanged(givenWidthInDp, givenHeightInDp)

        assertMessageDisplayedCalls(
            viewCallback = viewCallback,
            givenWidthInDp = givenWidthInDp,
            givenHeightInDp = givenHeightInDp
        )
        controller.currentMessage shouldBeEqualTo givenInAppMessage
        controller.contentWidthInDp shouldBeEqualTo givenWidthInDp
        controller.contentHeightInDp shouldBeEqualTo givenHeightInDp
    }

    @Test
    fun messageLoaded_givenWidthChanged_expectViewUpdated() {
        val controller = setupGistAndCreateViewController()
        val viewCallback = controller.initMockViewCallback()
        val givenElementId = "test-element-id"
        controller.elementId = givenElementId
        val givenInAppMessage = createInAppMessage(queueId = "1", elementId = givenElementId)
        messagingManager.dispatch(
            InAppMessagingAction.EmbedMessages(listOf(givenInAppMessage))
        ).flushCoroutines(scopeProviderStub.inAppLifecycleScope)
        val givenOldWidthInDp = Double.random(100.0, 500.0)
        val givenHeightInDp = Double.random(100.0, 500.0)
        val givenNewWidthInDp = givenOldWidthInDp + Double.random(10.0, 100.0)
        controller.sizeChanged(givenOldWidthInDp, givenHeightInDp)
        clearMocks(viewCallback)

        controller.sizeChanged(givenNewWidthInDp, givenHeightInDp)

        assertMessageDisplayedCalls(
            viewCallback = viewCallback,
            givenWidthInDp = givenNewWidthInDp,
            givenHeightInDp = givenHeightInDp
        )
        controller.currentMessage shouldBeEqualTo givenInAppMessage
        controller.contentWidthInDp shouldBeEqualTo givenNewWidthInDp
        controller.contentHeightInDp shouldBeEqualTo givenHeightInDp
    }

    @Test
    fun messageLoaded_givenHeightChanged_expectViewUpdated() {
        val controller = setupGistAndCreateViewController()
        val viewCallback = controller.initMockViewCallback()
        val givenElementId = "test-element-id"
        controller.elementId = givenElementId
        val givenInAppMessage = createInAppMessage(queueId = "1", elementId = givenElementId)
        messagingManager.dispatch(
            InAppMessagingAction.EmbedMessages(listOf(givenInAppMessage))
        ).flushCoroutines(scopeProviderStub.inAppLifecycleScope)
        val givenWidthInDp = Double.random(100.0, 500.0)
        val givenOldHeightInDp = Double.random(100.0, 500.0)
        val givenNewHeightInDp = givenOldHeightInDp + Double.random(10.0, 100.0)
        controller.sizeChanged(givenWidthInDp, givenOldHeightInDp)
        clearMocks(viewCallback)

        controller.sizeChanged(givenWidthInDp, givenNewHeightInDp)

        assertMessageDisplayedCalls(
            viewCallback = viewCallback,
            givenWidthInDp = givenWidthInDp,
            givenHeightInDp = givenNewHeightInDp
        )
        controller.currentMessage shouldBeEqualTo givenInAppMessage
        controller.contentWidthInDp shouldBeEqualTo givenWidthInDp
        controller.contentHeightInDp shouldBeEqualTo givenNewHeightInDp
    }

    @Test
    fun messageLoaded_givenSameSize_expectNoViewUpdate() {
        val controller = setupGistAndCreateViewController()
        val viewCallback = controller.initMockViewCallback()
        val givenElementId = "test-element-id"
        controller.elementId = givenElementId
        val givenInAppMessage = createInAppMessage(queueId = "1", elementId = givenElementId)
        messagingManager.dispatch(
            InAppMessagingAction.EmbedMessages(listOf(givenInAppMessage))
        ).flushCoroutines(scopeProviderStub.inAppLifecycleScope)
        val givenWidthInDp = Double.random(100.0, 500.0)
        val givenHeightInDp = Double.random(100.0, 500.0)
        controller.sizeChanged(givenWidthInDp, givenHeightInDp)
        clearMocks(viewCallback)

        controller.sizeChanged(givenWidthInDp, givenHeightInDp)

        assertNoInteractions(viewCallback)
        controller.currentMessage shouldBeEqualTo givenInAppMessage
        controller.contentWidthInDp shouldBeEqualTo givenWidthInDp
        controller.contentHeightInDp shouldBeEqualTo givenHeightInDp
    }

    @Test
    fun messageLoaded_givenSizeUpdateWithNoMessage_expectNoViewUpdate() {
        val controller = setupGistAndCreateViewController()
        val viewCallback = controller.initMockViewCallback()
        controller.elementId = "test-element-id"
        val givenWidthInDp = Double.random(100.0, 500.0)
        val givenHeightInDp = Double.random(100.0, 500.0)

        controller.sizeChanged(givenWidthInDp, givenHeightInDp)

        assertNoInteractions(viewCallback)
        controller.currentMessage.shouldBeNull()
        controller.contentWidthInDp.shouldBeNull()
        controller.contentHeightInDp.shouldBeNull()
    }

    @Test
    fun messageLoadingFailed_givenRouteError_expectViewDismissed() {
        val controller = setupGistAndCreateViewController()
        val viewCallback = controller.initMockViewCallback()
        val givenElementId = "test-element-id"
        controller.elementId = givenElementId
        val givenInAppMessage = createInAppMessage(queueId = "1", elementId = givenElementId)
        controller.currentMessage = givenInAppMessage
        messagingManager.dispatch(
            InAppMessagingAction.EmbedMessages(listOf(givenInAppMessage))
        ).flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        controller.routeError(String.random)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        assertMessageDismissedCalls(
            viewCallback = viewCallback
        )
    }

    @Test
    fun messageLoadingFailed_givenLoadError_expectViewDismissed() {
        val controller = setupGistAndCreateViewController()
        val viewCallback = controller.initMockViewCallback()
        val givenElementId = "test-element-id"
        controller.elementId = givenElementId
        val givenInAppMessage = createInAppMessage(queueId = "1", elementId = givenElementId)
        controller.currentMessage = givenInAppMessage
        messagingManager.dispatch(
            InAppMessagingAction.EmbedMessages(listOf(givenInAppMessage))
        ).flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        controller.error()
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        assertMessageDismissedCalls(
            viewCallback = viewCallback
        )
    }

    private fun assertMessageLoadingCalls(
        controller: InlineInAppMessageViewController,
        viewCallback: InlineInAppMessageViewCallback,
        expectedMessage: Message
    ) = verifyOrder {
        val expectedConfig = EngineWebConfiguration(
            siteId = moduleConfig.siteId,
            dataCenter = gistDataCenter,
            messageId = expectedMessage.messageId,
            instanceId = expectedMessage.instanceId,
            endpoint = gistEnvironment.getEngineApiUrl(),
            properties = expectedMessage.properties
        )

        elapsedTimer.start(any())
        viewCallback.onLoadingStarted()
        viewDelegate.createEngineWebViewInstance()
        engineWebViewDelegate.setAlpha(0.0F)
        engineWebViewDelegate.listener = controller
        viewDelegate.addView(engineWebViewDelegate)
        viewDelegate.isVisible = true
        engineWebViewDelegate.setup(expectedConfig)
    }

    private fun assertMessageDisplayedCalls(
        viewCallback: InlineInAppMessageViewCallback,
        givenWidthInDp: Double,
        givenHeightInDp: Double
    ) = verifyOrder {
        val expectedWidthInPx = (givenWidthInDp * screenDensity).roundToInt()
        val expectedHeightInPx = (givenHeightInDp * screenDensity).roundToInt()
        viewCallback.onViewSizeChanged(expectedWidthInPx, expectedHeightInPx)
        platformDelegate.animateViewSize(
            widthInDp = givenWidthInDp,
            heightInDp = givenHeightInDp,
            duration = null,
            onStart = any(),
            onEnd = any()
        )
        engineWebViewDelegate.setAlpha(1.0F)
        engineWebViewDelegate.bringToFront()
        viewCallback.onLoadingFinished()
        elapsedTimer.end()
    }

    private fun assertMessageDismissedCalls(
        viewCallback: InlineInAppMessageViewCallback
    ) = verifyOrder {
        engineWebViewDelegate.stopLoading()
        platformDelegate.animateViewSize(
            widthInDp = null,
            heightInDp = 0.0,
            duration = null,
            onStart = any(),
            onEnd = any()
        )
        viewDelegate.isVisible = false
        engineWebViewDelegate.listener = null
        viewDelegate.removeView(engineWebViewDelegate)
        engineWebViewDelegate.releaseResources()
        viewCallback.onNoMessageToDisplay()
    }
}

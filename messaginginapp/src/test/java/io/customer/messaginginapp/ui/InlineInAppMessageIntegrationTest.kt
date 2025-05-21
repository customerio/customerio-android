package io.customer.messaginginapp.ui

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.flushCoroutines
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.messaginginapp.gist.presentation.GistProvider
import io.customer.messaginginapp.gist.presentation.GistSdk
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InlineMessageState
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.messaginginapp.type.InlineMessageActionListener
import io.customer.messaginginapp.ui.InAppMessagingIntegrationTest.testDismiss
import io.customer.messaginginapp.ui.InAppMessagingIntegrationTest.testDisplay
import io.customer.messaginginapp.ui.InAppMessagingIntegrationTest.testLoadingFailed
import io.customer.messaginginapp.ui.InAppMessagingIntegrationTest.testMatchAndEmbed
import io.customer.messaginginapp.ui.InAppMessagingIntegrationTest.testTap
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.data.model.Region
import io.mockk.mockk
import io.mockk.spyk
import org.junit.jupiter.api.Test

/**
 * Integration tests for state transitions and the messaging manager
 * with inline in-app messaging.
 */
class InlineInAppMessageIntegrationTest : JUnitTest() {

    private val moduleConfig = MessagingInAppModuleConfig.Builder(
        siteId = "test-site-id",
        region = Region.US
    ).build()
    private val gistDataCenter = moduleConfig.region.code
    private val gistEnvironment = GistEnvironment.LOCAL
    private val scopeProviderStub = ScopeProviderStub.Standard()

    private lateinit var gistProvider: GistProvider
    private lateinit var messagingManager: InAppMessagingManager
    private lateinit var mockContext: android.content.Context
    private lateinit var mockListener: InlineMessageActionListener

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<GistQueue>(mockk(relaxed = true))
                        overrideDependency<ScopeProvider>(scopeProviderStub)
                        overrideDependency<Logger>(mockk(relaxed = true))
                    }
                }
            }
        )

        mockContext = mockk(relaxed = true)
        mockListener = mockk(relaxed = true)

        ModuleMessagingInApp(config = moduleConfig).attachToSDKComponent()

        gistProvider = GistSdk(
            siteId = moduleConfig.siteId,
            dataCenter = gistDataCenter,
            environment = gistEnvironment
        ).also { SDKComponent.overrideDependency<GistProvider>(it) }

        messagingManager = spyk(SDKComponent.inAppMessagingManager)
            .also { SDKComponent.overrideDependency<InAppMessagingManager>(it) }

        messagingManager.dispatch(
            InAppMessagingAction.Initialize(
                siteId = moduleConfig.siteId,
                dataCenter = moduleConfig.region.code,
                environment = gistEnvironment
            )
        )

        messagingManager.dispatch(
            InAppMessagingAction.SetUserIdentifier("test-user-id")
        )

        flushCoroutines(scopeProviderStub.inAppLifecycleScope)
    }

    @Test
    fun givenInlineMessage_whenEmbedAction_expectMessageInReadyToEmbedState() {
        val elementId = "test-element-id"
        val message = io.customer.messaginginapp.state.MessageBuilderTest.createMessage(
            elementId = elementId,
            routeRule = null
        )

        message.testMatchAndEmbed(
            elementId = elementId,
            manager = messagingManager,
            routeName = "test/route",
            scopeProvider = scopeProviderStub
        )

        val state = messagingManager.getCurrentState()
        val messageState = state.queuedInlineMessagesState.getMessage(elementId)

        assert(messageState is InlineMessageState.ReadyToEmbed) {
            "Message should be ready to embed, but was ${messageState?.javaClass?.simpleName}"
        }
    }

    @Test
    fun givenInlineMessage_whenTapAction_expectCorrectHandling() {
        val elementId = "test-element"
        val message = io.customer.messaginginapp.state.MessageBuilderTest.createMessage(
            elementId = elementId,
            routeRule = null
        )

        val routeName = "test-route"
        message.testMatchAndEmbed(
            elementId = elementId,
            manager = messagingManager,
            routeName = routeName,
            scopeProvider = scopeProviderStub
        )

        val actionName = "test-action"
        val buttonName = "test-button"

        message.testTap(
            manager = messagingManager,
            routeName = routeName,
            actionName = actionName,
            buttonName = buttonName,
            scopeProvider = scopeProviderStub
        )

        val state = messagingManager.getCurrentState()
        val messageState = state.queuedInlineMessagesState.getMessage(elementId)

        assert(messageState is InlineMessageState.ReadyToEmbed) {
            "Message should still be ready to embed after tap action"
        }
    }

    @Test
    fun givenInlineMessage_whenDisplayedAndDismissed_expectCorrectStateTransitions() {
        val elementId = "test-element"
        val message = io.customer.messaginginapp.state.MessageBuilderTest.createMessage(
            elementId = elementId,
            routeRule = null
        )

        message.testMatchAndEmbed(
            elementId = elementId,
            manager = messagingManager,
            routeName = "test/route",
            scopeProvider = scopeProviderStub
        )

        var state = messagingManager.getCurrentState()
        var messageState = state.queuedInlineMessagesState.getMessage(elementId)
        assert(messageState is InlineMessageState.ReadyToEmbed) {
            "Message should be ready to embed initially"
        }

        message.testDisplay(
            manager = messagingManager,
            scopeProvider = scopeProviderStub
        )

        state = messagingManager.getCurrentState()
        messageState = state.queuedInlineMessagesState.getMessage(elementId)
        assert(messageState is InlineMessageState.Embedded) {
            "Message should be embedded after display action"
        }

        message.testDismiss(
            manager = messagingManager,
            scopeProvider = scopeProviderStub
        )

        state = messagingManager.getCurrentState()
        messageState = state.queuedInlineMessagesState.getMessage(elementId)
        assert(messageState is InlineMessageState.Dismissed) {
            "Message should be dismissed after dismiss action"
        }
    }

    @Test
    fun givenInlineMessage_whenLoadingFails_expectStateDismissed() {
        val elementId = "test-element"
        val message = io.customer.messaginginapp.state.MessageBuilderTest.createMessage(
            elementId = elementId,
            routeRule = null
        )

        message.testMatchAndEmbed(
            elementId = elementId,
            manager = messagingManager,
            routeName = "test/route",
            scopeProvider = scopeProviderStub
        )

        message.testLoadingFailed(
            manager = messagingManager,
            scopeProvider = scopeProviderStub
        )

        val state = messagingManager.getCurrentState()
        val messageState = state.queuedInlineMessagesState.getMessage(elementId)
        assert(messageState is InlineMessageState.Dismissed) {
            "Message should be dismissed after loading failure"
        }
    }

    @Test
    fun givenPageRoute_whenSetPageRouteAction_expectRouteUpdated() {
        val routeName = "test/simple-route"

        messagingManager.dispatch(InAppMessagingAction.SetPageRoute(routeName))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        val state = messagingManager.getCurrentState()
        assert(state.currentRoute == routeName) {
            "Current route should be set to $routeName, but was ${state.currentRoute}"
        }
    }
}

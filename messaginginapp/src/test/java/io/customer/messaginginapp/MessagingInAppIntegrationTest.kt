package io.customer.messaginginapp

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.assertNoInteractions
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.di.gistApiProvider
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.listeners.Queue
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.presentation.GIST_TAG
import io.customer.messaginginapp.gist.presentation.GistModalManager
import io.customer.messaginginapp.gist.presentation.GistSdk
import io.customer.messaginginapp.provider.GistApi
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.messaginginapp.testutils.engine.GistApiProviderDelegate
import io.customer.messaginginapp.testutils.engine.GistEngineMessageDriver.MessageState
import io.customer.messaginginapp.testutils.engine.GistEngineWebViewClientInterceptor
import io.customer.messaginginapp.testutils.engine.GistServerResponseDispatcher
import io.customer.messaginginapp.testutils.extension.awaitWithTimeoutBlocking
import io.customer.messaginginapp.testutils.extension.createInAppMessage
import io.customer.messaginginapp.testutils.extension.getInAppMessageActivity
import io.customer.messaginginapp.testutils.extension.mapToInAppMessage
import io.customer.messaginginapp.testutils.extension.pageRuleContains
import io.customer.messaginginapp.testutils.extension.pageRuleEquals
import io.customer.messaginginapp.testutils.extension.runOnUIThreadBlocking
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.messaginginapp.type.InAppMessage
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.model.Region
import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockWebServer
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSingleItem
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@Ignore(
    "These test are ignored because they need more refactoring to produce reliable results. " +
        "The tests are flaky and need to be fixed."
)
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MessagingInAppIntegrationTest : IntegrationTest() {
    private lateinit var consumerAppActivityMock: Activity
    private lateinit var inAppEventListenerMock: InAppEventListener
    private lateinit var gistEnvironmentMock: GistEnvironment

    private val testCoroutineScope = TestScope(UnconfinedTestDispatcher())
    private val gistEngineClientInterceptor = GistEngineWebViewClientInterceptor(testCoroutineScope)
    private val gistWebServerResponseDispatcher = GistServerResponseDispatcher()

    private lateinit var gistWebServerMock: MockWebServer
    private lateinit var gistQueue: Queue
    private lateinit var gistModalManager: GistModalManager

    init {
        GistSdk.coroutineScope = testCoroutineScope
        GistSdk.engineWebViewClientInterceptor = gistEngineClientInterceptor
        every { applicationMock.checkCallingOrSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) } returns PackageManager.PERMISSION_GRANTED
    }

    override fun setup(testConfig: TestConfig) {
        setupGistMocks()
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<GistApi>(spyk(GistApiProviderDelegate()))
                    }
                }
            }
        )

        val gistApi = SDKComponent.gistApiProvider
        // Mocking Gist API initialization
        every { gistApi.initProvider(any(), any(), any()) } answers { call ->
            val args = call.invocation.args
            GistSdk.init(
                application = args[0] as Application,
                siteId = args[1] as String,
                dataCenter = args[2] as String,
                environment = gistEnvironmentMock
            )
        }

        ModuleMessagingInApp(
            config = MessagingInAppModuleConfig.Builder(
                siteId = TestConstants.Keys.SITE_ID,
                region = Region.US
            ).build()
        ).attachToSDKComponent().initialize()

        GistSdk.addListener(gistEngineClientInterceptor)
        gistQueue = GistSdk.gistQueue
        gistModalManager = GistSdk.gistModalManager
        testCoroutineScope.runOnUIThreadBlocking {
            SDKComponent.eventBus.publish(Event.ScreenViewedEvent(String.random))
            GistSdk.onActivityResumed(consumerAppActivityMock)
        }
    }

    private fun setupGistMocks() {
        consumerAppActivityMock = mockk(relaxed = true)
        inAppEventListenerMock = mockk(relaxed = true)
        gistEnvironmentMock = mockk()

        gistWebServerMock = MockWebServer().apply {
            dispatcher = gistWebServerResponseDispatcher
            start()
        }

        // Mocking InAppEventListener
        every { inAppEventListenerMock.errorWithMessage(any()) } answers { call ->
            val message: InAppMessage = call.invocation.args.first() as InAppMessage
            println("$GIST_TAG: Error with message: $message")
        }

        // Mocking Gist environment
        every { gistEnvironmentMock.getGistQueueApiUrl() } returns gistWebServerMock.url("/api/").toString()
        every { gistEnvironmentMock.getEngineApiUrl() } returns gistWebServerMock.url("/engine").toString()
        every { gistEnvironmentMock.getGistRendererUrl() } returns gistWebServerMock.url("/renderer").toString()
    }

    override fun teardown() {
        ensureMessageDismissed()
        testCoroutineScope.runOnUIThreadBlocking {
            SDKComponent.eventBus.publish(Event.ResetEvent)
            GistSdk.onActivityPaused(consumerAppActivityMock)
        }
        GistSdk.reset()

        gistWebServerMock.shutdown()
        gistWebServerResponseDispatcher.reset()
        gistEngineClientInterceptor.reset()

        super.teardown()
    }

    @Test
    fun stayOnSameScreenAfterMessageLoaded_givenNoAction_expectShowMessage() {
        val givenDashboardMessage = createInAppMessage(
            messageId = "welcome-banner",
            pageRule = pageRuleContains("Dashboard")
        )
        mockGistQueue(listOf(givenDashboardMessage))

        val messageDriver = gistEngineClientInterceptor.getMessageDriver(givenDashboardMessage)
        setCurrentRoute("Dashboard")
        messageDriver.messageStateDeferred(MessageState.COMPLETED).awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageVisible.shouldBeTrue()
        assertCalledOnce { inAppEventListenerMock.messageShown(mapToInAppMessage(givenDashboardMessage)) }
        confirmVerified(inAppEventListenerMock)

        GistSdk.dismissMessage()
        inAppMessageDisplayed.shouldBeNull()
        inAppMessagesQueued.shouldBeEmpty()
    }

    @Test
    fun navigateToDifferentScreenWhileMessageLoading_givenRouteDoesNotMatch_expectDoNotShowMessage() {
        val givenDashboardMessage = createInAppMessage(
            messageId = "welcome-banner",
            pageRule = pageRuleContains("Dashboard")
        )
        mockGistQueue(listOf(givenDashboardMessage))

        val messageDriver = gistEngineClientInterceptor.getMessageDriver(givenDashboardMessage)
        messageDriver.blockAt(MessageState.HTML_LOADED)
        setCurrentRoute("Dashboard")
        messageDriver.messageStateDeferred(MessageState.HTML_LOADED).awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageLoading.shouldBeTrue()
        isInAppMessageVisible.shouldBeFalse()

        setCurrentRoute("Settings")
        messageDriver.unblock()
        isInAppMessageLoading.shouldBeFalse()
        assertNoInteractions(inAppEventListenerMock)

        messageDriver.messageStateDeferred(MessageState.COMPLETED).awaitWithTimeoutBlocking()
        inAppMessagesQueued.shouldHaveSingleItem().shouldBeEqualTo(givenDashboardMessage)
        inAppMessageDisplayed.shouldBeNull()
        isInAppMessageLoading.shouldBeFalse()
        assertNoInteractions(inAppEventListenerMock)
    }

    @Test
    fun navigateToDifferentScreenWhileMessageLoading_givenRouteMatches_expectShowMessage() {
        val givenDashboardMessage = createInAppMessage(
            messageId = "welcome-banner",
            pageRule = pageRuleContains("Dashboard")
        )
        mockGistQueue(listOf(givenDashboardMessage))

        val messageDriver = gistEngineClientInterceptor.getMessageDriver(givenDashboardMessage)
        messageDriver.blockAt(MessageState.HTML_LOADED)
        setCurrentRoute("DashboardOne")
        messageDriver.messageStateDeferred(MessageState.HTML_LOADED).awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageLoading.shouldBeTrue()
        isInAppMessageVisible.shouldBeFalse()

        setCurrentRoute("DashboardTwo")
        messageDriver.unblock()
        messageDriver.messageStateDeferred(MessageState.COMPLETED).awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageVisible.shouldBeTrue()
        assertCalledOnce { inAppEventListenerMock.messageShown(mapToInAppMessage(givenDashboardMessage)) }
        confirmVerified(inAppEventListenerMock)
    }

    @Test
    fun navigateToDifferentScreenWhileMessageLoading_givenNoPageRules_expectShowMessage() {
        val givenNoPageRuleMessage = createInAppMessage(
            messageId = "welcome-banner",
            pageRule = null
        )
        mockGistQueue(listOf(givenNoPageRuleMessage))

        val messageDriver = gistEngineClientInterceptor.getMessageDriver(givenNoPageRuleMessage)
        messageDriver.blockAt(MessageState.HTML_LOADED)
        setCurrentRoute("Dashboard")
        messageDriver.messageStateDeferred(MessageState.HTML_LOADED).awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageLoading.shouldBeTrue()
        isInAppMessageVisible.shouldBeFalse()

        setCurrentRoute("Settings")
        messageDriver.unblock()
        messageDriver.messageStateDeferred(MessageState.COMPLETED).awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageVisible.shouldBeTrue()
        assertCalledOnce { inAppEventListenerMock.messageShown(mapToInAppMessage(givenNoPageRuleMessage)) }
        confirmVerified(inAppEventListenerMock)

        GistSdk.dismissMessage()
        inAppMessagesQueued.shouldBeEmpty()
    }

    @Test
    fun navigateToDifferentScreenWhileMessageLoading_givenRouteDoesNotMatch_expectShowMessageWhenNavigatedBack() {
        val givenDashboardMessage = createInAppMessage(
            messageId = "welcome-banner",
            pageRule = pageRuleContains("Dashboard")
        )
        mockGistQueue(listOf(givenDashboardMessage))

        val messageDriverAttemptOne =
            gistEngineClientInterceptor.getMessageDriver(givenDashboardMessage)
        messageDriverAttemptOne.blockAt(MessageState.HTML_LOADED)
        setCurrentRoute("Dashboard")
        messageDriverAttemptOne.messageStateDeferred(MessageState.HTML_LOADED)
            .awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageLoading.shouldBeTrue()
        isInAppMessageVisible.shouldBeFalse()

        setCurrentRoute("Settings")
        messageDriverAttemptOne.unblock()
        isInAppMessageLoading.shouldBeFalse()
        assertNoInteractions(inAppEventListenerMock)
        messageDriverAttemptOne.messageStateDeferred(MessageState.COMPLETED)
            .awaitWithTimeoutBlocking()
        inAppMessagesQueued.shouldHaveSingleItem().shouldBeEqualTo(givenDashboardMessage)
        inAppMessageDisplayed.shouldBeNull()

        setCurrentRoute("Dashboard")
        val messageDriverAttemptTwo =
            gistEngineClientInterceptor.getMessageDriver(givenDashboardMessage)
        messageDriverAttemptTwo.messageStateDeferred(MessageState.COMPLETED)
            .awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageVisible.shouldBeTrue()
        assertCalledOnce { inAppEventListenerMock.messageShown(mapToInAppMessage(givenDashboardMessage)) }
        confirmVerified(inAppEventListenerMock)
    }

    @Test
    fun navigateToDifferentScreenWhileMessageLoading_givenMultipleMessagesQueued_expectShowMessageOnlyWhenNavigatedBack() {
        val givenDashboardMessageOne = createInAppMessage(
            messageId = "welcome-banner",
            pageRule = pageRuleContains("Dashboard"),
            priority = 1
        )
        val givenDashboardMessageTwo = createInAppMessage(
            messageId = "promotion-banner",
            pageRule = pageRuleContains("Dashboard"),
            priority = 2
        )
        val givenDashboardInAppMessageOne = mapToInAppMessage(givenDashboardMessageOne)
        val givenDashboardInAppMessageTwo = mapToInAppMessage(givenDashboardMessageTwo)
        mockGistQueue(listOf(givenDashboardMessageOne, givenDashboardMessageTwo))

        val messageDriverOneAttemptOne =
            gistEngineClientInterceptor.getMessageDriver(givenDashboardMessageOne)
        messageDriverOneAttemptOne.blockAt(MessageState.HTML_LOADED)
        setCurrentRoute("Dashboard")
        messageDriverOneAttemptOne.messageStateDeferred(MessageState.HTML_LOADED)
            .awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageLoading.shouldBeTrue()
        isInAppMessageVisible.shouldBeFalse()

        setCurrentRoute("Settings")
        messageDriverOneAttemptOne.unblock()
        isInAppMessageLoading.shouldBeFalse()
        assertNoInteractions(inAppEventListenerMock)
        messageDriverOneAttemptOne.messageStateDeferred(MessageState.COMPLETED)
            .awaitWithTimeoutBlocking()
        inAppMessagesQueued.shouldHaveSize(2)
        inAppMessageDisplayed.shouldBeNull()

        setCurrentRoute("Dashboard")
        gistEngineClientInterceptor.getMessageDriver(givenDashboardMessageOne)
            .messageStateDeferred(MessageState.COMPLETED).awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageVisible.shouldBeTrue()
        assertCalledOnce { inAppEventListenerMock.messageShown(givenDashboardInAppMessageOne) }
        inAppMessagesQueued.shouldHaveSingleItem().shouldBeEqualTo(givenDashboardMessageTwo)
        ensureMessageDismissed()
        assertCalledOnce { inAppEventListenerMock.messageDismissed(givenDashboardInAppMessageOne) }
        confirmVerified(inAppEventListenerMock)

        gistQueue.fetchUserMessagesFromLocalStore()
        gistEngineClientInterceptor.getMessageDriver(givenDashboardMessageTwo)
            .messageStateDeferred(MessageState.COMPLETED).awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageVisible.shouldBeTrue()
        assertCalledOnce { inAppEventListenerMock.messageShown(givenDashboardInAppMessageTwo) }
        confirmVerified(inAppEventListenerMock)
    }

    @Test
    fun stayOnSameScreenAfterMessageLoaded_givenSameRouteSetAgain_expectShowMessage() {
        val givenDashboardMessage = createInAppMessage(
            messageId = "welcome-banner",
            pageRule = pageRuleContains("Dashboard")
        )
        mockGistQueue(listOf(givenDashboardMessage))

        val messageDriver = gistEngineClientInterceptor.getMessageDriver(givenDashboardMessage)
        messageDriver.blockAt(MessageState.HTML_LOADED)
        setCurrentRoute("Dashboard")
        messageDriver.messageStateDeferred(MessageState.HTML_LOADED).awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageLoading.shouldBeTrue()
        isInAppMessageVisible.shouldBeFalse()

        setCurrentRoute("Dashboard")
        messageDriver.unblock()
        messageDriver.messageStateDeferred(MessageState.COMPLETED).awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageVisible.shouldBeTrue()
        assertCalledOnce { inAppEventListenerMock.messageShown(mapToInAppMessage(givenDashboardMessage)) }
        confirmVerified(inAppEventListenerMock)
    }

    @Test
    fun navigateToDifferentScreenWhileMessageLoading_givenRouteMatchesForBothScreens_expectShowCorrectMessageMatchingPageRules() {
        val givenDashboardMessage = createInAppMessage(
            messageId = "welcome-banner",
            pageRule = pageRuleContains("Dashboard")
        )
        val givenSettingsMessage = createInAppMessage(
            messageId = "promotion-banner",
            pageRule = pageRuleEquals("Settings")
        )
        val givenDashboardInAppMessage = mapToInAppMessage(givenDashboardMessage)
        val givenSettingsInAppMessage = mapToInAppMessage(givenSettingsMessage)
        mockGistQueue(listOf(givenDashboardMessage, givenSettingsMessage))

        val messageDriverDashboardAttemptOne =
            gistEngineClientInterceptor.getMessageDriver(givenDashboardMessage)
        messageDriverDashboardAttemptOne.blockAt(MessageState.HTML_LOADED)
        setCurrentRoute("Dashboard")
        messageDriverDashboardAttemptOne.messageStateDeferred(MessageState.HTML_LOADED)
            .awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageLoading.shouldBeTrue()
        isInAppMessageVisible.shouldBeFalse()

        val messageDriverSettings =
            gistEngineClientInterceptor.getMessageDriver(givenSettingsMessage)
        setCurrentRoute("Settings")
        messageDriverDashboardAttemptOne.unblock()
        isInAppMessageLoading.shouldBeFalse()
        assertNoInteractions(inAppEventListenerMock)
        messageDriverSettings.messageStateDeferred(MessageState.COMPLETED)
            .awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageVisible.shouldBeTrue()
        assertCalledOnce { inAppEventListenerMock.messageShown(givenSettingsInAppMessage) }
        ensureMessageDismissed()
        assertCalledOnce { inAppEventListenerMock.messageDismissed(givenSettingsInAppMessage) }
        confirmVerified(inAppEventListenerMock)

        val messageDriverDashboardAttemptTwo =
            gistEngineClientInterceptor.getMessageDriver(givenDashboardMessage)
        setCurrentRoute("Dashboard")
        messageDriverDashboardAttemptTwo.messageStateDeferred(MessageState.COMPLETED)
            .awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageVisible.shouldBeTrue()
        assertCalledOnce { inAppEventListenerMock.messageShown(givenDashboardInAppMessage) }
        ensureMessageDismissed()
        assertCalledOnce { inAppEventListenerMock.messageDismissed(givenDashboardInAppMessage) }
        confirmVerified(inAppEventListenerMock)

        clearMocks(inAppEventListenerMock)
        setCurrentRoute("Profile")
        listOf(
            messageDriverDashboardAttemptOne.messageStateDeferred(MessageState.COMPLETED),
            messageDriverSettings.messageStateDeferred(MessageState.COMPLETED),
            messageDriverDashboardAttemptTwo.messageStateDeferred(MessageState.COMPLETED)
        ).awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldBeNull()
        isInAppMessageLoading.shouldBeFalse()
        assertNoInteractions(inAppEventListenerMock)
        inAppMessagesQueued.shouldBeEmpty()
    }

    // Helper methods and properties

    private val inAppMessageDisplayed: Message?
        get() = gistModalManager.currentMessage
    private val inAppMessagesQueued: List<Message>
        get() = gistQueue.localMessageStore

    private val isInAppMessageLoading: Boolean
        get() = getInAppMessageActivity() != null
    private val isInAppMessageVisible: Boolean
        get() = getInAppMessageActivity()?.isEngineVisible ?: false

    private fun setCurrentRoute(route: String) {
        GistSdk.setCurrentRoute(route)
    }

    private fun mockGistQueue(messages: List<Message>) {
        gistWebServerResponseDispatcher.setUserMessagesMockResponse { getQueueMessagesMock(messages) }
        gistQueue.fetchUserMessages()
    }

    private fun ensureMessageDismissed(timeMillis: Long = 1_000) {
        val inAppMessageActivity = getInAppMessageActivity() ?: return

        val timeElapsed = measureTimeMillis {
            GistSdk.dismissMessage()
            runBlocking {
                withTimeout(timeMillis) {
                    while (!inAppMessageActivity.isDestroyed) {
                        // Sleep for a short period before checking again
                        Thread.sleep(100)
                    }
                }
            }
        }
        println("$GIST_TAG: Message dismissed in ${timeElapsed}ms")
    }
}

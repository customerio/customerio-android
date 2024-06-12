package io.customer.messaginginapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseIntegrationTest
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.listeners.Queue
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.presentation.GIST_TAG
import io.customer.messaginginapp.gist.presentation.GistModalManager
import io.customer.messaginginapp.gist.presentation.GistSdk
import io.customer.messaginginapp.support.GistEngineMessageDriver.MessageState
import io.customer.messaginginapp.support.GistEngineWebViewClientInterceptor
import io.customer.messaginginapp.support.GistServerResponseDispatcher
import io.customer.messaginginapp.support.awaitWithTimeoutBlocking
import io.customer.messaginginapp.support.createInAppMessage
import io.customer.messaginginapp.support.getInAppMessageActivity
import io.customer.messaginginapp.support.mapToInAppMessage
import io.customer.messaginginapp.support.pageRuleContains
import io.customer.messaginginapp.support.pageRuleEquals
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.messaginginapp.type.InAppMessage
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
import org.amshove.kluent.shouldNotBeNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MessagingInAppIntegrationTest : BaseIntegrationTest() {
    private val inAppEventListenerMock: InAppEventListener = mock()
    private val gistEnvironmentMock: GistEnvironment = mock()

    private val testCoroutineScope = TestScope(UnconfinedTestDispatcher())
    private val gistEngineClientInterceptor = GistEngineWebViewClientInterceptor(testCoroutineScope)
    private val gistWebServerResponseDispatcher = GistServerResponseDispatcher()

    private lateinit var gistWebServerMock: MockWebServer
    private lateinit var gistQueue: Queue
    private lateinit var gistModalManager: GistModalManager

    init {
        GistSdk.coroutineScope = testCoroutineScope
        GistSdk.engineWebViewClientInterceptor = gistEngineClientInterceptor

        whenever(inAppEventListenerMock.errorWithMessage(any())).then { invocation ->
            val message: InAppMessage = invocation.arguments.first() as InAppMessage
            println("$GIST_TAG: Error with message: $message")
        }
    }

    @Before
    override fun setup() {
        modules.add(
            ModuleMessagingInApp(
                config = MessagingInAppModuleConfig.Builder()
                    .setEventListener(inAppEventListenerMock)
                    .build()
            )
        )

        super.setup()

        gistWebServerMock = MockWebServer().apply {
            dispatcher = gistWebServerResponseDispatcher
            start()
        }
        whenever(gistEnvironmentMock.getGistQueueApiUrl()).thenReturn(
            gistWebServerMock.url("/api/").toString()
        )
        whenever(gistEnvironmentMock.getEngineApiUrl()).thenReturn(
            gistWebServerMock.url("/engine").toString()
        )
        whenever(gistEnvironmentMock.getGistRendererUrl()).thenReturn(
            gistWebServerMock.url("/renderer").toString()
        )

        GistSdk.gistEnvironment = gistEnvironmentMock
        GistSdk.addListener(gistEngineClientInterceptor)
        gistQueue = GistSdk.gistQueue
        gistModalManager = GistSdk.gistModalManager
    }

    override fun teardown() {
        ensureMessageDismissed()
        GistSdk.reset()

        gistWebServerMock.shutdown()
        gistWebServerResponseDispatcher.reset()
        gistEngineClientInterceptor.reset()
        clearInvocations(inAppEventListenerMock)

        super.teardown()
    }

    @Test
    fun givenUserNavigatedToDifferentScreenWhileMessageLoading_expectDoNotShowModalMessage() {
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
        verifyNoInteractions(inAppEventListenerMock)

        messageDriver.messageStateDeferred(MessageState.COMPLETED).awaitWithTimeoutBlocking()
        inAppMessagesQueued.shouldHaveSingleItem().shouldBeEqualTo(givenDashboardMessage)
        inAppMessageDisplayed.shouldBeNull()
        isInAppMessageLoading.shouldBeFalse()
        verifyNoInteractions(inAppEventListenerMock)
    }

    @Test
    fun givenUserStillOnSameScreenAfterMessageLoads_expectShowModalMessage() {
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
        verify(inAppEventListenerMock).messageShown(mapToInAppMessage(givenDashboardMessage))
        verifyNoMoreInteractions(inAppEventListenerMock)

        GistSdk.dismissMessage()
        inAppMessageDisplayed.shouldBeNull()
        inAppMessagesQueued.shouldBeEmpty()
    }

    @Test
    fun givenMessageHasNoPageRules_givenUserNavigatedToDifferentScreenWhileMessageLoaded_expectShowModalMessage() {
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
        verify(inAppEventListenerMock).messageShown(mapToInAppMessage(givenNoPageRuleMessage))
        verifyNoMoreInteractions(inAppEventListenerMock)

        GistSdk.dismissMessage()
        inAppMessagesQueued.shouldBeEmpty()
    }

    @Test
    fun givenUserOnScreenDuringFetch_givenUserNavigatedToDifferentScreenWhileMessageLoading_expectShowModalMessageAfterGoBack() {
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
        verifyNoInteractions(inAppEventListenerMock)
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
        verify(inAppEventListenerMock).messageShown(mapToInAppMessage(givenDashboardMessage))
        verifyNoMoreInteractions(inAppEventListenerMock)
    }

    @Test
    fun givenRouteChangedToSameRoute_expectDoNotDismissModal() {
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
        verify(inAppEventListenerMock).messageShown(mapToInAppMessage(givenDashboardMessage))
        verifyNoMoreInteractions(inAppEventListenerMock)
    }

    @Test
    fun givenMultipleMessageQueued_givenUserNavigatedToDifferentScreenWhileMessageLoading_expectShowCorrectModalMessages() {
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
        verifyNoInteractions(inAppEventListenerMock)
        messageDriverSettings.messageStateDeferred(MessageState.COMPLETED)
            .awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageVisible.shouldBeTrue()
        verify(inAppEventListenerMock).messageShown(givenSettingsInAppMessage)
        ensureMessageDismissed()
        verify(inAppEventListenerMock).messageDismissed(givenSettingsInAppMessage)
        verifyNoMoreInteractions(inAppEventListenerMock)

        val messageDriverDashboardAttemptTwo =
            gistEngineClientInterceptor.getMessageDriver(givenDashboardMessage)
        setCurrentRoute("Dashboard")
        messageDriverDashboardAttemptTwo.messageStateDeferred(MessageState.COMPLETED)
            .awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldNotBeNull()
        isInAppMessageVisible.shouldBeTrue()
        verify(inAppEventListenerMock).messageShown(givenDashboardInAppMessage)
        ensureMessageDismissed()
        verify(inAppEventListenerMock).messageDismissed(givenDashboardInAppMessage)
        verifyNoMoreInteractions(inAppEventListenerMock)

        clearInvocations(inAppEventListenerMock)
        setCurrentRoute("Profile")
        listOf(
            messageDriverDashboardAttemptOne.messageStateDeferred(MessageState.COMPLETED),
            messageDriverSettings.messageStateDeferred(MessageState.COMPLETED),
            messageDriverDashboardAttemptTwo.messageStateDeferred(MessageState.COMPLETED)
        ).awaitWithTimeoutBlocking()
        inAppMessageDisplayed.shouldBeNull()
        isInAppMessageLoading.shouldBeFalse()
        verifyNoInteractions(inAppEventListenerMock)
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

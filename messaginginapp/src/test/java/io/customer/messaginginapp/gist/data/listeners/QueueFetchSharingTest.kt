package io.customer.messaginginapp.gist.data.listeners

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.flushCoroutines
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.response.QueueMessagesResponse
import io.customer.messaginginapp.inbox.data.InboxFetchOutcome
import io.customer.messaginginapp.inbox.data.InboxRepository
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.MessageBuilderMock.createMessage
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import okhttp3.Headers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response

/**
 * Verifies that queue fetches for the same identity share one in-flight request, that callers
 * needing newer data get one follow-up, and that a response for a previous identity is discarded.
 * Each service call is held open on a [CompletableDeferred] so overlap is controlled by the test,
 * not by timing.
 */
@RunWith(RobolectricTestRunner::class)
class QueueFetchSharingTest : IntegrationTest() {

    private val scopeProviderStub = ScopeProviderStub.Unconfined()
    private val mockRepository: InboxRepository = mockk(relaxed = true)
    private val mockQueueService: GistQueueService = mockk(relaxed = true)
    private val pendingResponses = mutableListOf<CompletableDeferred<Response<QueueMessagesResponse>>>()

    private lateinit var manager: InAppMessagingManager
    private lateinit var queue: Queue

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<ScopeProvider>(scopeProviderStub)
                        overrideDependency<InboxRepository>(mockRepository)
                    }
                }
            } + testConfig
        )

        coEvery { mockRepository.loadTemplatesAndBranding() } returns InboxFetchOutcome.Hidden("test")
        coEvery { mockQueueService.fetchMessagesForUser(any(), any()) } coAnswers {
            CompletableDeferred<Response<QueueMessagesResponse>>().also { pendingResponses += it }.await()
        }

        ModuleMessagingInApp(
            config = MessagingInAppModuleConfig.Builder(
                siteId = TestConstants.Keys.SITE_ID,
                region = io.customer.sdk.data.model.Region.US
            ).build()
        ).attachToSDKComponent()

        manager = SDKComponent.inAppMessagingManager
        manager.dispatch(
            InAppMessagingAction.Initialize(
                siteId = "site",
                dataCenter = "us",
                environment = GistEnvironment.PROD
            )
        )
        manager.dispatch(InAppMessagingAction.SetUserIdentifier("user-1"))
        flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        queue = Queue(queueServiceOverride = mockQueueService)
    }

    override fun teardown() {
        manager.dispatch(InAppMessagingAction.Reset)
        super.teardown()
    }

    private fun successResponse(messages: List<Message> = emptyList()): Response<QueueMessagesResponse> =
        mockk(relaxed = true) {
            every { code() } returns 200
            every { headers() } returns Headers.headersOf()
            every { isSuccessful() } returns true
            every { body() } returns QueueMessagesResponse(inAppMessages = messages)
        }

    // `createMessage` stubs a relaxed GistProperties, whose non-null `broadcast` makes the message
    // anonymous; the queue drops those from the server response, so pin it to a regular message.
    private fun regularMessage(elementId: String): Message =
        createMessage(elementId = elementId).also { every { it.gistProperties.broadcast } returns null }

    private fun completeRequest(index: Int, response: Response<QueueMessagesResponse> = successResponse()) {
        pendingResponses[index].complete(response)
        flushCoroutines(scopeProviderStub.inAppLifecycleScope)
    }

    @Test
    fun fetchUserMessages_givenRequestInFlightForSameUser_expectShared() {
        queue.fetchUserMessages()
        queue.fetchUserMessages()
        queue.fetchUserMessages()

        coVerify(exactly = 1) { mockQueueService.fetchMessagesForUser(any(), any()) }

        completeRequest(0)
        coVerify(exactly = 1) { mockQueueService.fetchMessagesForUser(any(), any()) }
    }

    @Test
    fun fetchUserMessages_givenPreviousRequestCompleted_expectNewRequest() {
        queue.fetchUserMessages()
        completeRequest(0)

        queue.fetchUserMessages()

        coVerify(exactly = 2) { mockQueueService.fetchMessagesForUser(any(), any()) }
    }

    @Test
    fun fetchUserMessages_givenFreshCallerJoinedInFlightRequest_expectOneFollowUp() {
        queue.fetchUserMessages()
        queue.fetchUserMessages(mustBeFresh = true)
        queue.fetchUserMessages(mustBeFresh = true)
        coVerify(exactly = 1) { mockQueueService.fetchMessagesForUser(any(), any()) }

        completeRequest(0)
        coVerify(exactly = 2) { mockQueueService.fetchMessagesForUser(any(), any()) }

        completeRequest(1)
        coVerify(exactly = 2) { mockQueueService.fetchMessagesForUser(any(), any()) }
    }

    @Test
    fun fetchUserMessages_givenSameUserThroughout_expectResponseApplied() {
        val message = regularMessage("promotion")
        queue.fetchUserMessages()

        completeRequest(0, successResponse(listOf(message)))

        assert(manager.getCurrentState().messagesInQueue.any { it.queueId == message.queueId }) {
            "expected the response for the current user to be applied"
        }
    }

    @Test
    fun fetchUserMessages_givenUserChangedWhileInFlight_expectResponseDiscarded() {
        val message = regularMessage("promotion")
        queue.fetchUserMessages()

        manager.dispatch(InAppMessagingAction.SetUserIdentifier("user-2"))
        flushCoroutines(scopeProviderStub.inAppLifecycleScope)
        queue.fetchUserMessages()
        // The new user's fetch does not share the previous user's request.
        coVerify(exactly = 2) { mockQueueService.fetchMessagesForUser(any(), any()) }

        completeRequest(0, successResponse(listOf(message)))

        assert(manager.getCurrentState().messagesInQueue.none { it.queueId == message.queueId }) {
            "expected the previous user's response to be discarded"
        }
        completeRequest(1)
    }
}

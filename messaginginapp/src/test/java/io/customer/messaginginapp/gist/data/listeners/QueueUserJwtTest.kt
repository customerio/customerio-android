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
import io.customer.messaginginapp.gist.data.NetworkUtilities
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.response.QueueMessagesResponse
import io.customer.messaginginapp.gist.presentation.GistProvider
import io.customer.messaginginapp.inbox.data.InboxFetchOutcome
import io.customer.messaginginapp.inbox.data.InboxRepository
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.MessageBuilderMock.createMessage
import io.customer.messaginginapp.state.UserJwt
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.messaginginapp.type.UserJwtRejectionReason
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Clock
import io.customer.sdk.core.util.ScopeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class QueueUserJwtTest : IntegrationTest() {

    private val scopeProviderStub = ScopeProviderStub.Unconfined()
    private val mockRepository: InboxRepository = mockk(relaxed = true)
    private val mockQueueService: GistQueueService = mockk(relaxed = true)
    private val mockGistProvider: GistProvider = mockk(relaxed = true)
    private val rejections = mutableListOf<Pair<String, UserJwtRejectionReason>>()
    private val responses = ArrayDeque<Response<QueueMessagesResponse>>()
    private var nowMillis = 1_000_000L
    private val clock: Clock = mockk(relaxed = true) { every { currentTimeMillis() } answers { nowMillis } }

    private lateinit var manager: InAppMessagingManager
    private lateinit var module: ModuleMessagingInApp
    private lateinit var queue: Queue

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<ScopeProvider>(scopeProviderStub)
                        overrideDependency<InboxRepository>(mockRepository)
                        overrideDependency<GistProvider>(mockGistProvider)
                        overrideDependency<Clock>(clock)
                    }
                }
            } + testConfig
        )

        coEvery { mockRepository.loadTemplatesAndBranding() } returns InboxFetchOutcome.Hidden("test")
        coEvery { mockQueueService.fetchMessagesForUser(any(), any()) } answers { responses.removeFirst() }

        module = ModuleMessagingInApp(
            config = MessagingInAppModuleConfig.Builder(siteId = TestConstants.Keys.SITE_ID)
                .setUserJwtListener { userId, reason -> rejections += userId to reason }
                .build()
        ).attachToSDKComponent()

        manager = SDKComponent.inAppMessagingManager
        manager.dispatch(InAppMessagingAction.Initialize(siteId = "site", dataCenter = "us", environment = GistEnvironment.PROD))
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

    private fun unauthorized(body: String): Response<QueueMessagesResponse> =
        Response.error(401, body.toResponseBody("application/json".toMediaType()))

    private fun rejected(reason: String) = unauthorized("""{"error":"user_jwt_rejected","reason":"$reason"}""")

    private fun fetch() {
        queue.fetchUserMessages()
        flushCoroutines(scopeProviderStub.inAppLifecycleScope)
    }

    private fun headersFor(): okhttp3.Headers =
        NetworkUtilities.addCommonHeaders(Request.Builder().url("https://example.com"), manager.getCurrentState()).build().headers

    private fun regularMessage(elementId: String): Message =
        createMessage(elementId = elementId).also { every { it.gistProperties.broadcast } returns null }

    @Test
    fun addCommonHeaders_givenJwtForIdentifiedUser_expectHeaderSent() {
        module.setUserJwt("user-1", "jwt-1")

        assertEquals("jwt-1", headersFor()[NetworkUtilities.USER_JWT_HEADER])
        assertNull(headersFor()[NetworkUtilities.AUTHORIZATION_HEADER])
    }

    @Test
    fun addCommonHeaders_givenNoJwt_expectNoHeader() {
        assertNull(headersFor()[NetworkUtilities.USER_JWT_HEADER])
    }

    @Test
    fun addCommonHeaders_givenJwtForAnotherUser_expectNoHeaderUntilThatUserIsIdentified() {
        module.setUserJwt("user-2", "jwt-2")
        assertNull(headersFor()[NetworkUtilities.USER_JWT_HEADER])

        manager.dispatch(InAppMessagingAction.Reset)
        manager.dispatch(InAppMessagingAction.SetUserIdentifier("user-2"))

        assertEquals("jwt-2", headersFor()[NetworkUtilities.USER_JWT_HEADER])
    }

    @Test
    fun reset_givenJwtForLoggedOutUser_expectDropped() {
        module.setUserJwt("user-1", "jwt-1")

        manager.dispatch(InAppMessagingAction.Reset)
        manager.dispatch(InAppMessagingAction.SetUserIdentifier("user-1"))

        assertNull(manager.getCurrentState().activeUserJwt)
    }

    @Test
    fun setUserJwt_givenBlankToken_expectCleared() {
        module.setUserJwt("user-1", "jwt-1")
        module.setUserJwt("user-1", "  ")

        assertNull(manager.getCurrentState().userJwt)
    }

    @Test
    fun setUserJwt_givenActiveJwtChanges_expectImmediateFetch() {
        module.setUserJwt("user-1", "jwt-1")
        module.setUserJwt("user-1", "jwt-1")
        // A JWT for a user who isn't identified stops the identified user's one being sent.
        module.setUserJwt("user-2", "jwt-2")

        io.mockk.verify(exactly = 2) { mockGistProvider.fetchInAppMessages() }
    }

    @Test
    fun setUserJwt_givenNewJwtAfterEachRejection_expectAtMostOneImmediateFetchPerPollInterval() {
        val pollInterval = manager.getCurrentState().pollInterval
        fun rejectThenHandOver(jwt: String) {
            responses += rejected("bad_subject")
            fetch()
            module.setUserJwt("user-1", jwt)
        }

        module.setUserJwt("user-1", "jwt-0")
        rejectThenHandOver("jwt-1")
        rejectThenHandOver("jwt-2")
        nowMillis += pollInterval
        rejectThenHandOver("jwt-3")

        // jwt-0 and jwt-1 fetch straight away, jwt-2 waits for the next poll, and jwt-3 comes a
        // polling interval after jwt-1.
        io.mockk.verify(exactly = 3) { mockGistProvider.fetchInAppMessages() }
        assertEquals("jwt-3", manager.getCurrentState().activeUserJwt)
    }

    @Test
    fun fetchUserMessages_givenEachRejectionReason_expectListenerToldTheReason() {
        val reasons = listOf("missing", "malformed", "bad_signature", "expired", "bad_subject", "something_new")
        val expected = listOf(
            UserJwtRejectionReason.MISSING,
            UserJwtRejectionReason.MALFORMED,
            UserJwtRejectionReason.BAD_SIGNATURE,
            UserJwtRejectionReason.EXPIRED,
            UserJwtRejectionReason.BAD_SUBJECT,
            UserJwtRejectionReason.UNKNOWN
        )

        reasons.forEachIndexed { index, reason ->
            module.setUserJwt("user-1", "jwt-$index")
            responses += rejected(reason)
            fetch()
        }

        assertEquals(expected.map { "user-1" to it }, rejections)
    }

    @Test
    fun fetchUserMessages_givenRejection_expectMessagesKeptAndFetchesPausedUntilNewJwt() {
        val message = regularMessage("promotion")
        module.setUserJwt("user-1", "jwt-1")
        responses += successResponse(listOf(message))
        fetch()

        responses += rejected("expired")
        fetch()
        fetch()
        fetch()

        coVerify(exactly = 2) { mockQueueService.fetchMessagesForUser(any(), any()) }
        assertEquals(1, rejections.size)
        assertTrue(manager.getCurrentState().isPausedByUserJwtRejection)
        assertTrue(manager.getCurrentState().messagesInQueue.any { it.queueId == message.queueId })

        module.setUserJwt("user-1", "jwt-2")
        responses += successResponse(listOf(message))
        fetch()

        assertFalse(manager.getCurrentState().isPausedByUserJwtRejection)
        coVerify(exactly = 3) { mockQueueService.fetchMessagesForUser(any(), any()) }
    }

    @Test
    fun fetchUserMessages_givenMissingJwtRejection_expectPausedUntilJwtHandedOver() {
        responses += rejected("missing")
        fetch()
        fetch()

        coVerify(exactly = 1) { mockQueueService.fetchMessagesForUser(any(), any()) }
        assertEquals(listOf("user-1" to UserJwtRejectionReason.MISSING), rejections)

        module.setUserJwt("user-1", "jwt-1")
        responses += successResponse()
        fetch()

        coVerify(exactly = 2) { mockQueueService.fetchMessagesForUser(any(), any()) }
    }

    @Test
    fun fetchUserMessages_givenPlainUnauthorized_expectNoPauseAndNoListener() {
        module.setUserJwt("user-1", "jwt-1")
        responses += unauthorized("""{"error":"unknown_key"}""")
        fetch()
        responses += unauthorized("")
        fetch()
        responses += successResponse()
        fetch()

        assertFalse(manager.getCurrentState().isPausedByUserJwtRejection)
        assertTrue(rejections.isEmpty())
        coVerify(exactly = 3) { mockQueueService.fetchMessagesForUser(any(), any()) }
    }

    @Test
    fun fetchUserMessages_givenJwtChangedWhileInFlight_expectResponseDiscarded() {
        val pending = kotlinx.coroutines.CompletableDeferred<Response<QueueMessagesResponse>>()
        coEvery { mockQueueService.fetchMessagesForUser(any(), any()) } coAnswers { pending.await() }
        module.setUserJwt("user-1", "jwt-1")
        queue.fetchUserMessages()

        module.setUserJwt("user-1", "jwt-2")
        pending.complete(successResponse(listOf(regularMessage("promotion"))))
        flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        assertTrue(manager.getCurrentState().messagesInQueue.isEmpty())
    }

    @Test
    fun userJwtRejected_givenStaleRejectionForPreviousJwt_expectIgnored() {
        module.setUserJwt("user-1", "jwt-2")

        manager.dispatch(InAppMessagingAction.UserJwtRejected(io.customer.messaginginapp.state.UserJwtRejection("user-1", "jwt-1")))

        assertFalse(manager.getCurrentState().isPausedByUserJwtRejection)
    }

    @Test
    fun stateToString_givenJwt_expectTokenNeverPrinted() {
        manager.dispatch(InAppMessagingAction.SetUserJwt(UserJwt("user-1", "secret-token")))

        assertFalse(manager.getCurrentState().toString().contains("secret-token"))
        assertFalse(QueueFetchKey.from(manager.getCurrentState()).toString().contains("secret-token"))
        assertFalse(QueueFetchKey.from(manager.getCurrentState())!!.cacheOwner.contains("secret-token"))
    }
}

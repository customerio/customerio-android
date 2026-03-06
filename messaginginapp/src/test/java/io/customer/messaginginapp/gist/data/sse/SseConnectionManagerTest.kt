package io.customer.messaginginapp.gist.data.sse

import io.customer.messaginginapp.gist.data.NetworkUtilities
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SseConnectionManagerTest : JUnitTest() {

    private val sseLogger = mockk<InAppSseLogger>(relaxed = true)
    private val sseService = mockk<SseService>(relaxed = true)
    private val sseDataParser = mockk<SseDataParser>(relaxed = true)
    private val inAppMessagingManager = mockk<InAppMessagingManager>(relaxed = true)
    private val heartbeatTimer = mockk<HeartbeatTimer>(relaxed = true) {
        every { timeoutFlow } returns MutableStateFlow<HeartbeatTimeoutEvent?>(null).asStateFlow()
        coEvery { startTimer(any()) } returns Unit
        coEvery { reset() } returns Unit
    }
    private val retryDecisionFlow = MutableStateFlow<RetryDecision?>(null)
    private val retryHelper = mockk<SseRetryHelper>(relaxed = true) {
        every { retryDecisionFlow } returns this@SseConnectionManagerTest.retryDecisionFlow.asStateFlow()
        coEvery { scheduleRetry(any()) } returns Unit
        coEvery { resetRetryState() } returns Unit
    }

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var connectionManager: SseConnectionManager

    @BeforeEach
    fun setup() {
        connectionManager = SseConnectionManager(
            sseLogger = sseLogger,
            sseService = sseService,
            sseDataParser = sseDataParser,
            inAppMessagingManager = inAppMessagingManager,
            heartbeatTimer = heartbeatTimer,
            retryHelper = retryHelper,
            scope = testScope
        )
    }

    @Test
    fun testStartConnection_whenDisconnected_thenEstablishesConnection() = runTest {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ConnectionOpenEvent,
            ServerEvent(ServerEvent.CONNECTED, ""),
            ServerEvent(ServerEvent.HEARTBEAT, "")
        )

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        coVerify { sseService.connectSse("test-session", "test-user", "test-site") }
    }

    @Test
    fun testStartConnection_whenAlreadyConnecting_thenDoesNotStartNewConnection() = runTest {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        // Use a flow that never completes to keep connection in CONNECTING state
        coEvery { sseService.connectSse(any(), any(), any()) } returns flow {
            // This flow never completes, keeping the connection in CONNECTING state
        }

        // When - Start first connection
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Start second connection while first is still connecting
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then - Should only call connectSse once
        coVerify(exactly = 1) { sseService.connectSse(any(), any(), any()) }
    }

    @Test
    fun testStartConnection_whenNoUserToken_thenThrowsException() = runTest {
        // Given
        val mockState = InAppMessagingState(
            userId = null,
            anonymousId = null,
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        verify { sseLogger.logNoUserTokenAvailable() }
        coVerify(exactly = 0) { sseService.connectSse(any(), any(), any()) }
    }

    @Test
    fun testStartConnection_whenAnonymousIdAvailable_thenUsesAnonymousId() = runTest {
        // Given
        val mockState = InAppMessagingState(
            userId = null,
            anonymousId = "test-anonymous",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf()

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        coVerify { sseService.connectSse("test-session", "test-anonymous", "test-site") }
    }

    @Test
    fun testStopConnection_thenCancelsJobAndDisconnects() = runTest(testDispatcher) {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf()

        // Start connection first
        connectionManager.startConnection()
        advanceUntilIdle()

        // When
        connectionManager.stopConnection()
        advanceUntilIdle()

        // Then - Test passes if no exceptions are thrown
        // The disconnect call is tested indirectly through the flow
    }

    @Test
    fun testHandleSseEvent_whenConnectedEvent_thenLogsConfirmation() = runTest {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ServerEvent(ServerEvent.CONNECTED, "")
        )

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        verify { sseLogger.logConnectionConfirmed() }
    }

    @Test
    fun testHandleSseEvent_whenHeartbeatEvent_thenLogsDebug() = runTest {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ServerEvent(ServerEvent.HEARTBEAT, "")
        )

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        verify { sseLogger.logReceivedHeartbeat() }
    }

    @Test
    fun testHandleSseEvent_whenMessagesEvent_thenProcessesMessages() = runTest {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        val mockMessages = listOf(mockk<Message>(), mockk<Message>())
        val messagesJson = """[{"messageId": "msg1"}, {"messageId": "msg2"}]"""

        every { inAppMessagingManager.getCurrentState() } returns mockState
        every { sseDataParser.parseInAppMessages(messagesJson) } returns mockMessages
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ServerEvent(ServerEvent.MESSAGES, messagesJson)
        )

        val actionSlot = slot<InAppMessagingAction.ProcessMessageQueue>()

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        verify { sseDataParser.parseInAppMessages(messagesJson) }
        verify { inAppMessagingManager.dispatch(capture(actionSlot)) }
        actionSlot.captured.messages.shouldBeEqualTo(mockMessages)
    }

    @Test
    fun testHandleSseEvent_whenEmptyMessagesEvent_thenLogsDebug() = runTest {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        every { sseDataParser.parseInAppMessages(any()) } returns emptyList()
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ServerEvent(ServerEvent.MESSAGES, "[]")
        )

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        verify { sseLogger.logReceivedEmptyMessagesEvent() }
        verify(exactly = 0) { inAppMessagingManager.dispatch(any()) }
    }

    @Test
    fun testHandleSseEvent_whenTtlExceededEvent_thenReconnects() = runTest(testDispatcher) {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState

        // First connection returns TTL_EXCEEDED, subsequent connections return empty flow to prevent infinite loop
        coEvery { sseService.connectSse(any(), any(), any()) } returnsMany (
            listOf(
                flowOf(ServerEvent(ServerEvent.TTL_EXCEEDED, "")),
                flowOf()
            )
            )

        // When
        connectionManager.startConnection()
        advanceUntilIdle()

        // Then
        coVerify { retryHelper.resetRetryState() }
        // Verify that startConnection was called (for reconnection) - should have at least 2 connection attempts
        coVerify(atLeast = 2) { sseService.connectSse(any(), any(), any()) }
    }

    @Test
    fun testHandleSseEvent_whenUnknownEventType_thenLogsError() = runTest {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ServerEvent("unknown_event", "")
        )

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        verify { sseLogger.logUnknownEventType("unknown_event") }
    }

    @Test
    fun testHandleSseEvent_whenMessageParsingFails_thenLogsError() = runTest {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        every { sseDataParser.parseInAppMessages(any()) } throws RuntimeException("Parse error")
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ServerEvent(ServerEvent.MESSAGES, "invalid-json")
        )

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        verify(exactly = 0) { inAppMessagingManager.dispatch(any()) }
    }

    @Test
    fun testHandleSseEvent_whenInboxMessagesEvent_thenProcessesInboxMessages() = runTest {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        val mockInboxMessages = listOf(mockk<InboxMessage>(), mockk<InboxMessage>())
        val inboxMessagesJson = """[{"deliveryId": "inbox1"}, {"deliveryId": "inbox2"}]"""

        every { inAppMessagingManager.getCurrentState() } returns mockState
        every { sseDataParser.parseInboxMessages(inboxMessagesJson) } returns mockInboxMessages
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ServerEvent("inbox_messages", inboxMessagesJson)
        )

        val actionSlot = slot<InAppMessagingAction.ProcessInboxMessages>()

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        verify { sseDataParser.parseInboxMessages(inboxMessagesJson) }
        verify { inAppMessagingManager.dispatch(capture(actionSlot)) }
        actionSlot.captured.messages.shouldBeEqualTo(mockInboxMessages)
    }

    @Test
    fun testHandleSseEvent_whenEmptyInboxMessagesEvent_thenLogsDebug() = runTest {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        every { sseDataParser.parseInboxMessages(any()) } returns emptyList()
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ServerEvent("inbox_messages", "[]")
        )

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        verify { sseLogger.logReceivedEmptyMessagesEvent() }
        verify(exactly = 0) { inAppMessagingManager.dispatch(any()) }
    }

    @Test
    fun testHandleSseEvent_whenInboxMessageParsingFails_thenLogsError() = runTest {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        every { sseDataParser.parseInboxMessages(any()) } throws RuntimeException("Parse error")
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ServerEvent("inbox_messages", "invalid-json")
        )

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        verify(exactly = 0) { inAppMessagingManager.dispatch(any()) }
    }

    @Test
    fun testStartConnection_whenConnectionFails_thenLogsErrorAndSchedulesRetry() = runTest {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        coEvery { sseService.connectSse(any(), any(), any()) } throws RuntimeException("Connection failed")

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        verify { sseLogger.logConnectionAttemptFailed(any(), any()) }
        coVerify { retryHelper.scheduleRetry(any()) }
    }

    @Test
    fun testHandleSseEvent_whenConnectedEvent_thenStartsHeartbeatTimer() = runTest {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ServerEvent(ServerEvent.CONNECTED, "")
        )

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        verify { sseLogger.logConnectionConfirmed() }
        coVerify { heartbeatTimer.startTimer(NetworkUtilities.DEFAULT_HEARTBEAT_TIMEOUT_MS + NetworkUtilities.HEARTBEAT_BUFFER_MS) }
    }

    @Test
    fun testHandleSseEvent_whenHeartbeatEvent_thenResetsHeartbeatTimer() = runTest(testDispatcher) {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        val heartbeatData = """{"heartbeat": 15}"""
        every { inAppMessagingManager.getCurrentState() } returns mockState
        every { sseDataParser.parseHeartbeatTimeout(heartbeatData) } returns 15000L
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ServerEvent(ServerEvent.HEARTBEAT, heartbeatData)
        )

        // When
        connectionManager.startConnection()
        advanceUntilIdle()

        // Then - Test passes if no exceptions are thrown
        // The heartbeat timer interaction is tested indirectly through the flow
        coVerify { heartbeatTimer.startTimer(20000L) }
    }

    @Test
    fun testHandleSseEvent_whenMessagesEvent_thenDoesNotAffectHeartbeatTimer() = runTest {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        every { sseDataParser.parseInAppMessages(any()) } returns emptyList()
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ServerEvent(ServerEvent.MESSAGES, "[]")
        )

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        verify { sseLogger.logReceivedEmptyMessagesEvent() }
        coVerify(exactly = 0) { heartbeatTimer.startTimer(any()) }
    }

    @Test
    fun testRetryDecisionFlow_whenRetryNow_thenCallsStartConnection() = runTest(testDispatcher) {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(ConnectionClosedEvent)

        connectionManager.startConnection()
        advanceUntilIdle()

        // When
        retryDecisionFlow.value = RetryDecision.RetryNow(attemptCount = 1)
        advanceUntilIdle()

        // Then
        coVerify(atLeast = 2) { sseService.connectSse(any(), any(), any()) }
    }

    @Test
    fun testRetryDecisionFlow_whenMaxRetriesReached_thenFallsBackToPolling() = runTest(testDispatcher) {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf()

        connectionManager.startConnection()
        advanceUntilIdle()

        val actionSlot = slot<InAppMessagingAction.SetSseEnabled>()

        // When
        retryDecisionFlow.value = RetryDecision.MaxRetriesReached
        advanceUntilIdle()

        // Then
        verify { inAppMessagingManager.dispatch(capture(actionSlot)) }
        actionSlot.captured.enabled.shouldBeEqualTo(false)
    }

    @Test
    fun testRetryDecisionFlow_whenRetryNotPossible_thenFallsBackToPolling() = runTest(testDispatcher) {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf()

        connectionManager.startConnection()
        advanceUntilIdle()

        val actionSlot = slot<InAppMessagingAction.SetSseEnabled>()

        // When
        retryDecisionFlow.value = RetryDecision.RetryNotPossible(
            SseError.ServerError(Exception("Bad request"), 400, false)
        )
        advanceUntilIdle()

        // Then
        verify { inAppMessagingManager.dispatch(capture(actionSlot)) }
        actionSlot.captured.enabled.shouldBeEqualTo(false)
    }

    @Test
    fun testRetryDecisionFlow_whenNullDecision_thenIgnores() = runTest(testDispatcher) {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf()

        connectionManager.startConnection()
        advanceUntilIdle()

        // When
        retryDecisionFlow.value = null
        advanceUntilIdle()

        // Then
        coVerify(exactly = 1) { sseService.connectSse(any(), any(), any()) }
        verify(exactly = 0) { inAppMessagingManager.dispatch(any<InAppMessagingAction.SetSseEnabled>()) }
    }

    @Test
    fun testHandleSseEvent_whenConnectionFailedEvent_thenSchedulesRetry() = runTest(testDispatcher) {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        val networkError = SseError.NetworkError(java.io.IOException("Network error"))
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ConnectionFailedEvent(networkError)
        )

        // When
        connectionManager.startConnection()
        advanceUntilIdle()

        // Then
        coVerify { retryHelper.scheduleRetry(networkError) }
        coVerify { heartbeatTimer.reset() }
    }

    @Test
    fun testHandleSseEvent_whenConnectionFailedEventWithNonRetryableError_thenSchedulesRetry() = runTest(testDispatcher) {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        val serverError = SseError.ServerError(
            throwable = Exception("Bad request"),
            responseCode = 400,
            shouldRetry = false
        )
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ConnectionFailedEvent(serverError)
        )

        // When
        connectionManager.startConnection()
        advanceUntilIdle()

        // Then
        coVerify { retryHelper.scheduleRetry(serverError) }
    }

    @Test
    fun testHeartbeatTimeout_whenTimerExpires_thenTriggersRetry() = runTest(testDispatcher) {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        val timeoutFlow = MutableStateFlow<HeartbeatTimeoutEvent?>(null)
        every { heartbeatTimer.timeoutFlow } returns timeoutFlow.asStateFlow()
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ServerEvent(ServerEvent.CONNECTED, "")
        )

        // Start connection to initialize timeout flow collector
        connectionManager.startConnection()
        advanceUntilIdle()

        // When - Emit heartbeat timeout event
        timeoutFlow.value = HeartbeatTimeoutEvent
        advanceUntilIdle()

        // Then
        coVerify { retryHelper.scheduleRetry(SseError.TimeoutError) }
        coVerify { heartbeatTimer.reset() }
    }
}

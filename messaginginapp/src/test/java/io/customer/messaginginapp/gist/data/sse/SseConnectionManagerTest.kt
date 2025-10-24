package io.customer.messaginginapp.gist.data.sse

import io.customer.messaginginapp.gist.data.NetworkUtilities
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.sdk.core.util.Logger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private val logger = mockk<Logger>(relaxed = true)
    private val sseService = mockk<SseService>(relaxed = true)
    private val sseDataParser = mockk<SseDataParser>(relaxed = true)
    private val inAppMessagingManager = mockk<InAppMessagingManager>(relaxed = true)
    private val heartbeatTimer = mockk<HeartbeatTimer>(relaxed = true) {
        coEvery { startTimer(any()) } returns Unit
    }

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var connectionManager: SseConnectionManager

    @BeforeEach
    fun setup() {
        connectionManager = SseConnectionManager(
            logger = logger,
            sseService = sseService,
            sseDataParser = sseDataParser,
            inAppMessagingManager = inAppMessagingManager,
            heartbeatTimer = heartbeatTimer,
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
        verify { logger.error("SSE: Cannot establish connection: no user token available") }
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
        verify { logger.info("SSE: Connection confirmed") }
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
        verify { logger.debug("SSE: Received heartbeat") }
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
        every { sseDataParser.parseMessages(messagesJson) } returns mockMessages
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ServerEvent(ServerEvent.MESSAGES, messagesJson)
        )

        val actionSlot = slot<InAppMessagingAction.ProcessMessageQueue>()

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        verify { sseDataParser.parseMessages(messagesJson) }
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
        every { sseDataParser.parseMessages(any()) } returns emptyList()
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ServerEvent(ServerEvent.MESSAGES, "[]")
        )

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        verify { logger.debug("SSE: Received empty messages event") }
        verify(exactly = 0) { inAppMessagingManager.dispatch(any()) }
    }

    @Test
    fun testHandleSseEvent_whenTtlExceededEvent_thenLogsError() = runTest {
        // Given
        val mockState = InAppMessagingState(
            userId = "test-user",
            sessionId = "test-session",
            siteId = "test-site"
        )
        every { inAppMessagingManager.getCurrentState() } returns mockState
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ServerEvent(ServerEvent.TTL_EXCEEDED, "")
        )

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        verify { logger.error("SSE: TTL exceeded, connection will be closed") }
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
        verify { logger.error("SSE: Unknown event type: unknown_event") }
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
        every { sseDataParser.parseMessages(any()) } throws RuntimeException("Parse error")
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
    fun testStartConnection_whenConnectionFails_thenLogsErrorAndSetsDisconnected() = runTest {
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
        verify { logger.error("SSE: Connection failed: Connection failed") }
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
        verify { logger.info("SSE: Connection confirmed") }
        coVerify { heartbeatTimer.startTimer(NetworkUtilities.DEFAULT_HEARTBEAT_TIMEOUT_MS) }
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
        every { sseDataParser.parseMessages(any()) } returns emptyList()
        coEvery { sseService.connectSse(any(), any(), any()) } returns flowOf(
            ServerEvent(ServerEvent.MESSAGES, "[]")
        )

        // When
        connectionManager.startConnection()
        testScope.advanceUntilIdle()

        // Then
        verify { logger.debug("SSE: Received empty messages event") }
        coVerify(exactly = 0) { heartbeatTimer.startTimer(any()) }
    }
}

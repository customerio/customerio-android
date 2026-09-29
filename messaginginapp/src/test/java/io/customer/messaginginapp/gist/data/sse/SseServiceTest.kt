package io.customer.messaginginapp.gist.data.sse

import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SseServiceTest : IntegrationTest() {
    @Test
    fun testConnectSse_whenCallIsCancelledWhileCollected_thenFlowCompletes() {
        runBlocking {
            val server = MockWebServer()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("event: connected\ndata: {}\n\n")
                    .setSocketPolicy(SocketPolicy.KEEP_OPEN)
            )
            server.start()
            mockkObject(GistEnvironment.PROD)

            try {
                every { GistEnvironment.PROD.getSseApiUrl() } returns
                    server.url("/api/v3/sse").toString()
                val state =
                    InAppMessagingState(
                        siteId = "test-site",
                        dataCenter = "us",
                        environment = GistEnvironment.PROD,
                        userId = "test-user",
                        sessionId = "test-session"
                    )
                val service =
                    SseService(
                        sseLogger = mockk(relaxed = true),
                        inAppMessagingManager = mockk {
                            every { getCurrentState() } returns state
                        }
                    )
                val opened = CompletableDeferred<Unit>()
                val collecting =
                    async {
                        service.connectSse(state.sessionId, state.userId.orEmpty(), state.siteId)
                            .onEach { if (it == ConnectionOpenEvent) opened.complete(Unit) }
                            .toList()
                    }

                withTimeout(5_000) { opened.await() }
                service.disconnect()
                withTimeout(5_000) { collecting.await() }
            } finally {
                unmockkObject(GistEnvironment.PROD)
                server.shutdown()
            }
        }
    }

    @Test
    fun testConnectSse_whenServerReturnsEventStream_thenEmitsOpenAndServerEvent() {
        runBlocking {
            val server = MockWebServer()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("event: connected\ndata: {}\n\n")
            )
            server.start()
            mockkObject(GistEnvironment.PROD)

            try {
                every { GistEnvironment.PROD.getSseApiUrl() } returns
                    server.url("/api/v3/sse").toString()

                val state =
                    InAppMessagingState(
                        siteId = "test-site",
                        dataCenter = "us",
                        environment = GistEnvironment.PROD,
                        userId = "test-user",
                        sessionId = "test-session"
                    )
                val inAppMessagingManager =
                    mockk<InAppMessagingManager> {
                        every { getCurrentState() } returns state
                    }
                val service =
                    SseService(
                        sseLogger = mockk(relaxed = true),
                        inAppMessagingManager = inAppMessagingManager
                    )

                val events =
                    withTimeout(5_000) {
                        service
                            .connectSse(
                                sessionId = state.sessionId,
                                userToken = state.userId.orEmpty(),
                                siteId = state.siteId
                            ).take(3)
                            .toList()
                    }

                events[0] shouldBeEqualTo ConnectionOpenEvent
                events[1] shouldBeEqualTo ServerEvent(ServerEvent.CONNECTED, "{}")
                events[2] shouldBeEqualTo ConnectionClosedEvent

                val request = server.takeRequest()
                request.headers["Accept"] shouldBeEqualTo "text/event-stream"
                request.headers["Cache-Control"] shouldBeEqualTo "no-cache"
                request.headers["X-CIO-Client-Platform"] shouldBeEqualTo "android-android"
                request.requestUrl?.queryParameter("sessionId") shouldBeEqualTo "test-session"
                request.requestUrl?.queryParameter("siteId") shouldBeEqualTo "test-site"
                request.requestUrl?.queryParameter("userToken") shouldBeEqualTo "dGVzdC11c2Vy"
            } finally {
                unmockkObject(GistEnvironment.PROD)
                server.shutdown()
            }
        }
    }

    @Test
    fun testConnectSse_whenServerRejectsRequest_thenEmitsClassifiedFailure() {
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(401))
            server.start()
            mockkObject(GistEnvironment.PROD)

            try {
                every { GistEnvironment.PROD.getSseApiUrl() } returns
                    server.url("/api/v3/sse").toString()

                val state =
                    InAppMessagingState(
                        siteId = "test-site",
                        dataCenter = "us",
                        environment = GistEnvironment.PROD,
                        userId = "test-user",
                        sessionId = "test-session"
                    )
                val service =
                    SseService(
                        sseLogger = mockk(relaxed = true),
                        inAppMessagingManager =
                        mockk {
                            every { getCurrentState() } returns state
                        }
                    )

                val event =
                    withTimeout(5_000) {
                        service
                            .connectSse(
                                sessionId = state.sessionId,
                                userToken = state.userId.orEmpty(),
                                siteId = state.siteId
                            ).take(1)
                            .toList()
                            .single()
                    }

                event.shouldBeInstanceOf<ConnectionFailedEvent>()
                val error = (event as ConnectionFailedEvent).error
                error.shouldBeInstanceOf<SseError.ServerError>()
                (error as SseError.ServerError).responseCode shouldBeEqualTo 401
                error.shouldRetry shouldBeEqualTo false
            } finally {
                unmockkObject(GistEnvironment.PROD)
                server.shutdown()
            }
        }
    }

    @Test
    fun testConnectSse_whenStreamEndsMidEvent_thenEmitsRetryableFailure() {
        runBlocking {
            val server = MockWebServer()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("event: messages\ndata: incomplete")
            )
            server.start()
            mockkObject(GistEnvironment.PROD)

            try {
                every { GistEnvironment.PROD.getSseApiUrl() } returns
                    server.url("/api/v3/sse").toString()

                val state =
                    InAppMessagingState(
                        siteId = "test-site",
                        dataCenter = "us",
                        environment = GistEnvironment.PROD,
                        userId = "test-user",
                        sessionId = "test-session"
                    )
                val service =
                    SseService(
                        sseLogger = mockk(relaxed = true),
                        inAppMessagingManager =
                        mockk {
                            every { getCurrentState() } returns state
                        }
                    )

                val events =
                    withTimeout(5_000) {
                        service
                            .connectSse(
                                sessionId = state.sessionId,
                                userToken = state.userId.orEmpty(),
                                siteId = state.siteId
                            ).take(2)
                            .toList()
                    }

                events[0] shouldBeEqualTo ConnectionOpenEvent
                events[1].shouldBeInstanceOf<ConnectionFailedEvent>()
                (events[1] as ConnectionFailedEvent).error.shouldBeInstanceOf<SseError.NetworkError>()
                (events[1] as ConnectionFailedEvent).error.shouldRetry shouldBe true
            } finally {
                unmockkObject(GistEnvironment.PROD)
                server.shutdown()
            }
        }
    }

    @Test
    fun testConnectSse_whenEventHandlingThrowsRuntimeException_thenEmitsRetryableFailure() {
        runBlocking {
            val server = MockWebServer()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("event: messages\ndata: {}\n\n")
            )
            server.start()
            mockkObject(GistEnvironment.PROD)

            try {
                every { GistEnvironment.PROD.getSseApiUrl() } returns
                    server.url("/api/v3/sse").toString()

                val state =
                    InAppMessagingState(
                        siteId = "test-site",
                        dataCenter = "us",
                        environment = GistEnvironment.PROD,
                        userId = "test-user",
                        sessionId = "test-session"
                    )
                val logger = mockk<InAppSseLogger>(relaxed = true)
                every { logger.logReceivedEvent(any()) } throws IllegalStateException("parser callback failed")
                val service =
                    SseService(
                        sseLogger = logger,
                        inAppMessagingManager =
                        mockk {
                            every { getCurrentState() } returns state
                        }
                    )

                val events =
                    withTimeout(5_000) {
                        service
                            .connectSse(
                                sessionId = state.sessionId,
                                userToken = state.userId.orEmpty(),
                                siteId = state.siteId
                            ).take(2)
                            .toList()
                    }

                events[0] shouldBeEqualTo ConnectionOpenEvent
                events[1].shouldBeInstanceOf<ConnectionFailedEvent>()
                val error = (events[1] as ConnectionFailedEvent).error
                error.shouldBeInstanceOf<SseError.ServerError>()
                (error as SseError.ServerError).responseCode shouldBeEqualTo 200
                error.shouldRetry shouldBe true
            } finally {
                unmockkObject(GistEnvironment.PROD)
                server.shutdown()
            }
        }
    }
}

package io.customer.messaginginapp.gist.data.sse

import android.util.Base64
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.NetworkUtilities
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.sdk.core.util.Logger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * SSE service for establishing Server-Sent Events connections.
 *
 * This service handles the actual HTTP connection to the SSE endpoint and provides
 * a flow of SSE events for processing by the connection manager.
 *
 * Thread Safety: This service is designed to be called from thread-safe methods
 * (like SseConnectionManager methods) to avoid unnecessary synchronization overhead.
 * The SseConnectionManager ensures single-threaded access through its mutex.
 */
internal class SseService(
    private val logger: Logger,
    private val environment: GistEnvironment,
    private val state: InAppMessagingState
) {

    private var eventSource: EventSource? = null
    private val httpClient = createSseHttpClient()

    /**
     * Connect to SSE endpoint and return a flow of events.
     *
     * This method is NOT thread-safe. It should only be called from thread-safe methods
     * (like SseConnectionManager methods) to avoid unnecessary synchronization overhead.
     */
    fun connectSse(
        sessionId: String,
        userToken: String,
        siteId: String
    ): Flow<SseEvent> = callbackFlow {
        logger.debug("SSE: Connecting to SSE endpoint: ${environment.getSseApiUrl()}")

        val request = createSseRequest(sessionId, userToken, siteId)

        eventSource = EventSources.createFactory(httpClient)
            .newEventSource(
                request,
                object : EventSourceListener() {

                    override fun onOpen(eventSource: EventSource, response: Response) {
                        logger.info("SSE: Connection opened successfully")
                    }

                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String
                    ) {
                        logger.debug("SSE: Received event - id: $id type: $type, data: $data")

                        if (type.isNullOrBlank() || data.isBlank()) {
                            logger.debug("SSE: Received event with no type or data")
                            return
                        }

                        try {
                            trySend(SseEvent(type, data))
                        } catch (e: Exception) {
                            logger.debug("SSE: Error sending event: ${e.message}")
                        }
                    }

                    override fun onFailure(
                        eventSource: EventSource,
                        t: Throwable?,
                        response: Response?
                    ) {
                        logger.error("SSE: Connection failed: ${t?.message}, response code: ${response?.code}")
                        close(t ?: IllegalStateException("SSE failed: HTTP ${response?.code}"))
                    }

                    override fun onClosed(eventSource: EventSource) {
                        logger.info("SSE: Connection closed")
                        close()
                    }
                }
            )

        awaitClose {
            logger.debug("SSE: Flow cancelled, cleaning up")
            eventSource?.cancel()
            eventSource = null
        }
    }.buffer(Channel.BUFFERED)

    /**
     * Disconnect from SSE endpoint and clean up resources.
     *
     * This method is NOT thread-safe. It should only be called from thread-safe methods
     * (like SseConnectionManager.stopConnection) to avoid unnecessary synchronization overhead.
     */
    fun disconnect() {
        logger.debug("SSE: Disconnecting service")
        eventSource?.cancel()
        eventSource = null
    }

    private fun createSseHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(NetworkUtilities.SSE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val networkRequest = originalRequest.newBuilder()
                NetworkUtilities.addCommonHeaders(networkRequest, state, includeUserToken = false) // SSE uses userToken in URL, not header
                networkRequest.addHeader(NetworkUtilities.SSE_ACCEPT_HEADER, NetworkUtilities.SSE_ACCEPT_VALUE)
                networkRequest.addHeader(NetworkUtilities.SSE_CACHE_CONTROL_HEADER, NetworkUtilities.SSE_CACHE_CONTROL_VALUE)
                val finalRequest = networkRequest.build()

                chain.proceed(finalRequest)
            }
            .build()
    }

    private fun createSseRequest(
        sessionId: String,
        userToken: String,
        siteId: String
    ): Request {
        val encodedUserToken = Base64.encodeToString(userToken.toByteArray(), Base64.NO_WRAP)

        val url = environment.getSseApiUrl().toHttpUrl().newBuilder()
            .addQueryParameter(NetworkUtilities.SSE_SESSION_ID_PARAM, sessionId)
            .addQueryParameter(NetworkUtilities.SSE_SITE_ID_PARAM, siteId)
            .addQueryParameter(NetworkUtilities.SSE_USER_TOKEN_PARAM, encodedUserToken)
            .build()

        logger.debug("SSE: Creating request to: $url")

        return Request.Builder()
            .url(url)
            .get()
            .build()
    }
}

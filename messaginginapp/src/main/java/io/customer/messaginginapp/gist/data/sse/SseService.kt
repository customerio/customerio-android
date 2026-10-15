package io.customer.messaginginapp.gist.data.sse

import android.util.Base64
import io.customer.messaginginapp.gist.data.NetworkUtilities
import io.customer.messaginginapp.state.InAppMessagingManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

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
    private val sseLogger: InAppSseLogger,
    private val inAppMessagingManager: InAppMessagingManager
) {
    private var call: Call? = null
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
    ): Flow<SseEvent> =
        callbackFlow {
            val request = createSseRequest(sessionId, userToken, siteId)

            val currentCall = httpClient.newCall(request)
            currentCall.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: java.io.IOException
                    ) {
                        if (call.isCanceled()) {
                            close()
                            return
                        }
                        sendFailure(e, null)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response
                    ) {
                        try {
                            response.use {
                                val responseBody = response.body
                                if (!response.isSuccessful || responseBody == null || !response.isEventStream()) {
                                    sendFailure(null, response)
                                    return
                                }

                                sseLogger.logConnectionOpened()
                                val openResult = trySend(ConnectionOpenEvent)
                                if (!openResult.isSuccess) {
                                    sseLogger.logFailedToSendConnectionOpenedEvent(openResult.exceptionOrNull()?.message)
                                }

                                SseEventReader(responseBody.source()) { type, data ->
                                    sseLogger.logReceivedEvent(type)

                                    if (type.isNullOrBlank() || data.isBlank()) {
                                        sseLogger.logReceivedEventWithNoTypeOrData()
                                        return@SseEventReader
                                    }

                                    val eventResult = trySend(ServerEvent(type, data))
                                    if (!eventResult.isSuccess) {
                                        sseLogger.logFailedToSendEvent(eventResult.exceptionOrNull()?.message)
                                    }
                                }.process()

                                if (!call.isCanceled()) {
                                    sseLogger.logConnectionClosed()
                                    val closedResult = trySend(ConnectionClosedEvent)
                                    if (!closedResult.isSuccess) {
                                        sseLogger.logFailedToSendConnectionClosedEvent()
                                    }
                                }
                                close()
                            }
                        } catch (e: Exception) {
                            if (!call.isCanceled()) {
                                sendFailure(e, response)
                            } else {
                                close()
                            }
                        }
                    }

                    private fun sendFailure(
                        throwable: Throwable?,
                        response: Response?
                    ) {
                        sseLogger.logConnectionFailed(throwable?.message, response?.code)

                        val result = trySend(ConnectionFailedEvent(classifySseError(throwable, response)))
                        if (!result.isSuccess) {
                            sseLogger.logFailedToSendErrorEvent(result.exceptionOrNull()?.message)
                        }

                        // Close normally because the failure has already been delivered as an event.
                        close()
                    }
                }
            )

            // Update the shared field for disconnect() method, but capture locally for awaitClose.
            call = currentCall

            awaitClose {
                sseLogger.logFlowCancelled()
                currentCall.cancel()
                if (call == currentCall) {
                    call = null
                }
            }
        }.buffer(Channel.BUFFERED)

    /**
     * Disconnect from SSE endpoint and clean up resources.
     *
     * This method is NOT thread-safe. It should only be called from thread-safe methods
     * (like SseConnectionManager.stopConnection) to avoid unnecessary synchronization overhead.
     */
    fun disconnect() {
        sseLogger.logDisconnectingService()
        call?.cancel()
        call = null
    }

    private fun createSseHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .readTimeout(NetworkUtilities.SSE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val networkRequest = originalRequest.newBuilder()
                val currentState = inAppMessagingManager.getCurrentState()
                NetworkUtilities.addCommonHeaders(networkRequest, currentState, includeUserToken = false, includeAuthorization = false) // SSE uses userToken and key in URL, not header
                networkRequest.addHeader(NetworkUtilities.SSE_ACCEPT_HEADER, NetworkUtilities.SSE_ACCEPT_VALUE)
                networkRequest.addHeader(NetworkUtilities.SSE_CACHE_CONTROL_HEADER, NetworkUtilities.SSE_CACHE_CONTROL_VALUE)
                val finalRequest = networkRequest.build()

                chain.proceed(finalRequest)
            }.build()

    private fun createSseRequest(
        sessionId: String,
        userToken: String,
        siteId: String
    ): Request {
        val encodedUserToken = Base64.encodeToString(userToken.toByteArray(), Base64.NO_WRAP)
        val currentState = inAppMessagingManager.getCurrentState()
        val environment = currentState.environment

        val urlBuilder =
            environment
                .getSseApiUrl()
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter(NetworkUtilities.SSE_SESSION_ID_PARAM, sessionId)
        // siteId is optional when the SDK is set up with a public key
        if (siteId.isNotBlank()) {
            urlBuilder.addQueryParameter(NetworkUtilities.SSE_SITE_ID_PARAM, siteId)
        }
        currentState.publicKey?.let { key ->
            urlBuilder.addQueryParameter(NetworkUtilities.SSE_KEY_PARAM, key)
        }
        val url =
            urlBuilder
                .addQueryParameter(NetworkUtilities.SSE_USER_TOKEN_PARAM, encodedUserToken)
                .build()

        sseLogger.logCreatingRequest(url.toString())

        return Request
            .Builder()
            .url(url)
            .get()
            .build()
    }

    private fun Response.isEventStream(): Boolean {
        val mediaType = body?.contentType() ?: return false
        return mediaType.type == "text" && mediaType.subtype == "event-stream"
    }
}

/**
 * Represents an SSE event from the server connection
 */
internal sealed interface SseEvent

/**
 * Represents a connection opened event (emitted by SseService.onOpen).
 */
internal object ConnectionOpenEvent : SseEvent

/**
 * Represents a server event with type and data.
 *
 * @property eventType The type of event (connected, heartbeat, messages, ttl_exceeded)
 * @property data The JSON data associated with the event
 */
internal data class ServerEvent(
    val eventType: String,
    val data: String
) : SseEvent {
    companion object {
        // Server event types
        const val CONNECTED = "connected"
        const val HEARTBEAT = "heartbeat"
        const val MESSAGES = "messages"
        const val INBOX_MESSAGES = "inbox_messages"
        const val TTL_EXCEEDED = "ttl_exceeded"
    }
}

/**
 * Represents error events that occur during SSE connection or communication.
 */
internal class ConnectionFailedEvent(
    val error: SseError
) : SseEvent

internal object ConnectionClosedEvent : SseEvent

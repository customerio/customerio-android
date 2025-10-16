package io.customer.messaginginapp.gist.data.listeners

import android.util.Base64
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.sdk.core.util.Logger
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

internal class GistSSEClient(
    private val logger: Logger,
    private val inAppMessagingManager: InAppMessagingManager
) {

    private val networkLoggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    private val networkClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(networkLoggingInterceptor)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val sessionId: String = UUID.randomUUID().toString()
    private var eventSource: EventSource? = null

    private fun logMessage(message: String) {
        logger.debug("[DEV][SSE] $message")
    }

    fun checkForSSEStatus() {
        val configRequest = Request.Builder()
            .url(CONSUMER_STATUS_URL)
            .build()
        networkClient.newCall(configRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                logMessage("Failed to fetch config: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        logMessage("Config call failed: ${it.code}")
                        return
                    }

                    val useSse = it.header(SSE_STATUS_HEADER)?.toBoolean() ?: false

                    if (useSse) {
                        logMessage("SSE enabled, starting listener...")
                        startListening()
                    } else {
                        logMessage("SSE disabled, fallback to regular flow")
                    }
                }
            }
        })
    }

    fun startListening() {
        logMessage("Starting SSE client to listen for events...")
        if (eventSource != null) {
            logMessage("Already listening to SSE")
            return
        }

        val state = inAppMessagingManager.getCurrentState()
        val userId = state.userId
        if (userId.isNullOrBlank()) {
            logMessage("User ID is empty, cannot start SSE listener")
            return
        }
        val userToken = Base64.encodeToString(userId.toByteArray(), Base64.NO_WRAP)

        val url = SSE_CONNECTION_URL.toHttpUrl().newBuilder()
            .addQueryParameter("sessionId", sessionId)
            .addQueryParameter("siteId", state.siteId)
            .addQueryParameter("userToken", userToken)
            .build()
        val request = Request.Builder()
            .url(url)
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                logMessage("Connected to SSE stream")

                val enableSSE = response.header(SSE_STATUS_HEADER)?.toBoolean() ?: false
                if (!enableSSE) {
                    logMessage("SSE disabled by server, stopping listener")
//                    stopListening()
                }
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                logMessage("Received event: $data")

                try {
                    if (data.contains("heartbeat")) {
                        val json = Json.parseToJsonElement(data).jsonObject
                        val heartbeat = json["heartbeat"]?.jsonPrimitive?.longOrNull
                        logMessage("Heartbeat received: $heartbeat")
                    }
                } catch (ex: Exception) {
                    logMessage("Failed to parse event, e: $data, ex: ${ex.message}")
                }
            }

            override fun onClosed(eventSource: EventSource) {
                logMessage("Connection closed")
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                logMessage("SSE failure: ${t?.message}")
            }
        }

        eventSource = EventSources.createFactory(networkClient).newEventSource(request, listener)
        logMessage("SSE client started.")
    }

    fun stopListening() {
        logMessage("Stopping listening to SSE")
        eventSource?.cancel()
        eventSource = null
        logMessage("Stopped listening to SSE")
    }

    companion object {
        private const val CONSUMER_STATUS_URL = "https://consumer.cloud.gist.build/api/v3/users"
        private const val SSE_CONNECTION_URL = "https://realtime.cloud.gist.build/api/v3/sse"
        private const val SSE_STATUS_HEADER = "X-CIO-Use-SSE"
    }
}

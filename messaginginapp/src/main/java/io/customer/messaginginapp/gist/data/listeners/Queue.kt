package io.customer.messaginginapp.gist.data.listeners

import android.content.Context
import io.customer.messaginginapp.di.anonymousMessageManager
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.di.inAppPreferenceStore
import io.customer.messaginginapp.di.inAppSseLogger
import io.customer.messaginginapp.gist.data.AnonymousMessageManager
import io.customer.messaginginapp.gist.data.NetworkUtilities
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.QueueMessagesResponse
import io.customer.messaginginapp.gist.data.model.isMessageAnonymous
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.store.InAppPreferenceStore
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

internal interface GistQueueService {
    @POST("/api/v4/users")
    suspend fun fetchMessagesForUser(@Body body: Any = Object(), @Query("sessionId") sessionId: String): Response<QueueMessagesResponse>

    @POST("/api/v1/logs/message/{messageId}")
    suspend fun logMessageView(@Path("messageId") messageId: String, @Query("sessionId") sessionId: String)

    @POST("/api/v1/logs/queue/{queueId}")
    suspend fun logUserMessageView(@Path("queueId") queueId: String, @Query("sessionId") sessionId: String)

    @PATCH("/api/v1/messages/{queueId}")
    suspend fun logInboxMessageOpened(@Path("queueId") queueId: String, @Body body: Map<String, Boolean>)
}

interface GistQueue {
    fun fetchUserMessages()
    fun logView(message: Message)
    fun logOpenedStatus(message: InboxMessage, opened: Boolean)
}

class Queue : GistQueue {

    private val inAppMessagingManager = SDKComponent.inAppMessagingManager
    private val state: InAppMessagingState
        get() = inAppMessagingManager.getCurrentState()
    private val logger: Logger = SDKComponent.logger
    private val scope: CoroutineScope = SDKComponent.scopeProvider.inAppLifecycleScope
    private val application: Context
        get() = SDKComponent.android().applicationContext
    private val inAppPreferenceStore: InAppPreferenceStore
        get() = SDKComponent.inAppPreferenceStore
    private val anonymousMessageManager: AnonymousMessageManager
        get() = SDKComponent.anonymousMessageManager

    private val cacheSize = 10 * 1024 * 1024 // 10 MB
    private val cacheDirectory by lazy { File(application.cacheDir, "http_cache") }
    private val cache by lazy { Cache(cacheDirectory, cacheSize.toLong()) }

    private val gistQueueService by lazy {
        createGistQueueService()
    }

    private fun createGistQueueService(): GistQueueService {
        val httpClient = OkHttpClient.Builder()
            .cache(cache)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val networkRequest = originalRequest.newBuilder()
                NetworkUtilities.addCommonHeaders(networkRequest, state)
                networkRequest.header("Cache-Control", "no-cache")
                val finalRequest = networkRequest.build()

                interceptResponse(chain.proceed(finalRequest), originalRequest)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(state.environment.getGistQueueApiUrl())
            .addConverterFactory(GsonConverterFactory.create())
            .client(httpClient)
            .build()
            .create(GistQueueService::class.java)
    }

    private fun interceptResponse(response: okhttp3.Response, originalRequest: okhttp3.Request): okhttp3.Response {
        return when (response.code) {
            200 -> interceptSuccessfulResponse(response, originalRequest)
            304 -> interceptNotModifiedResponse(response, originalRequest)
            else -> response
        }
    }

    private fun interceptSuccessfulResponse(response: okhttp3.Response, originalRequest: okhttp3.Request): okhttp3.Response {
        response.body?.let { responseBody ->
            val responseBodyString = responseBody.string()
            inAppPreferenceStore.saveNetworkResponse(originalRequest.url.toString(), responseBodyString)
            return response.newBuilder()
                .body(responseBodyString.toResponseBody(responseBody.contentType()))
                .build()
        }
        return response
    }

    private fun interceptNotModifiedResponse(response: okhttp3.Response, originalRequest: okhttp3.Request): okhttp3.Response {
        val cachedResponse = inAppPreferenceStore.getNetworkResponse(originalRequest.url.toString())
        return cachedResponse?.let {
            response.newBuilder()
                .body(it.toResponseBody(null))
                .code(200)
                .build()
        } ?: response
    }

    override fun fetchUserMessages() {
        scope.launch {
            try {
                logger.debug("Fetching user messages")
                val latestMessagesResponse = gistQueueService.fetchMessagesForUser(sessionId = state.sessionId)

                val code = latestMessagesResponse.code()
                when {
                    (code == 204 || code == 304) -> handleNoContent(code)
                    latestMessagesResponse.isSuccessful -> handleSuccessfulFetch(latestMessagesResponse.body())
                    else -> handleFailedFetch(code)
                }

                updatePollingInterval(latestMessagesResponse.headers())
                updateSseFlag(latestMessagesResponse.headers())
            } catch (e: Exception) {
                logger.debug("Error fetching messages: ${e.message}")
            }
        }
    }

    private fun handleNoContent(responseCode: Int) {
        logger.debug("No messages found for user with response code: $responseCode")
        inAppMessagingManager.dispatch(InAppMessagingAction.ClearMessageQueue)
    }

    private fun handleSuccessfulFetch(responseBody: QueueMessagesResponse?) {
        if (responseBody == null) {
            logger.error("Received null response body for successful fetch")
            return
        }

        responseBody.let { response ->
            // Process in-app messages first
            val inAppMessages = response.inAppMessages
            logger.debug("Found ${inAppMessages.count()} in-app messages for user")
            // Store anonymous messages locally for frequency management
            anonymousMessageManager.updateAnonymousMessagesLocalStore(inAppMessages)

            // Get eligible anonymous messages from local storage (respects frequency/dismissal rules)
            val eligibleAnonymousMessages = anonymousMessageManager.getEligibleAnonymousMessages()

            // Filter out anonymous messages from server response (we use local ones instead)
            val regularMessages = inAppMessages.filter { !it.isMessageAnonymous() }

            // Combine regular messages with eligible anonymous messages
            val allMessages = regularMessages + eligibleAnonymousMessages

            logger.debug("Processing ${regularMessages.size} regular messages and ${eligibleAnonymousMessages.size} eligible anonymous messages")

            // Process all in-app messages through the normal queue
            inAppMessagingManager.dispatch(InAppMessagingAction.ProcessMessageQueue(allMessages))

            // Process inbox messages next
            val inboxMessages = response.inboxMessages
            logger.debug("Found ${inboxMessages.count()} inbox messages for user")
            inAppMessagingManager.dispatch(InAppMessagingAction.ProcessInboxMessages(inboxMessages))
        }
    }

    private fun handleFailedFetch(responseCode: Int) {
        logger.error("Failed to fetch messages: $responseCode")
        inAppMessagingManager.dispatch(InAppMessagingAction.ClearMessageQueue)
    }

    private fun updatePollingInterval(headers: Headers) {
        headers["X-Gist-Queue-Polling-Interval"]?.toIntOrNull()?.let { pollingIntervalSeconds ->
            if (pollingIntervalSeconds > 0) {
                val newPollingIntervalMilliseconds = (pollingIntervalSeconds * 1000).toLong()
                if (newPollingIntervalMilliseconds != state.pollInterval) {
                    logger.debug("Polling interval changed to: $pollingIntervalSeconds seconds")
                    inAppMessagingManager.dispatch(InAppMessagingAction.SetPollingInterval(newPollingIntervalMilliseconds))
                }
            }
        }
    }

    private fun updateSseFlag(headers: Headers) {
        val sseHeaderValue = headers["X-CIO-Use-SSE"]
        val sseEnabled = sseHeaderValue?.lowercase()?.toBooleanStrictOrNull() ?: false

        if (sseEnabled != state.sseEnabled) {
            SDKComponent.inAppSseLogger.logSseFlagChangedFromTo(state.sseEnabled, sseEnabled)
            inAppMessagingManager.dispatch(InAppMessagingAction.SetSseEnabled(sseEnabled))
        }
    }

    override fun logView(message: Message) {
        scope.launch {
            try {
                logger.debug("Logging view for message: $message")
                if (message.queueId != null) {
                    gistQueueService.logUserMessageView(message.queueId, sessionId = state.sessionId)
                } else {
                    gistQueueService.logMessageView(message.messageId, sessionId = state.sessionId)
                }
            } catch (e: Exception) {
                logger.debug("Failed to log message view: ${e.message}")
            }
        }
    }

    override fun logOpenedStatus(message: InboxMessage, opened: Boolean) {
        val queueId = message.queueId ?: run {
            logger.error("Cannot update inbox message ${message.deliveryId}: missing queueId")
            return
        }

        scope.launch {
            try {
                logger.debug("Updating inbox message $queueId opened status to: $opened")
                gistQueueService.logInboxMessageOpened(
                    queueId = queueId,
                    body = mapOf("opened" to opened)
                )
            } catch (e: Exception) {
                logger.error("Failed to update inbox message opened status: ${e.message}")
            }
        }
    }
}

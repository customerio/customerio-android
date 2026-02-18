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
import io.customer.messaginginapp.gist.data.model.isMessageAnonymous
import io.customer.messaginginapp.gist.data.model.response.QueueMessagesResponse
import io.customer.messaginginapp.gist.data.model.response.toDomain
import io.customer.messaginginapp.gist.data.model.response.toLogString
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
    suspend fun logInboxMessageOpened(@Path("queueId") queueId: String, @Query("sessionId") sessionId: String, @Body body: Map<String, Boolean>)
}

internal interface GistQueue {
    fun fetchUserMessages()
    fun logView(message: Message)
    fun logOpenedStatus(message: InboxMessage, opened: Boolean)
    fun logDeleted(message: InboxMessage)
}

internal class Queue : GistQueue {

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

    // Populates 304 response body with cached data and converts to 200 for Retrofit compatibility.
    // Retrofit only populates body() for 2xx responses, so we must convert 304->200.
    // Adds custom header to track that this was originally a 304 response.
    private fun interceptNotModifiedResponse(response: okhttp3.Response, originalRequest: okhttp3.Request): okhttp3.Response {
        val cachedResponse = inAppPreferenceStore.getNetworkResponse(originalRequest.url.toString())
        return cachedResponse?.let {
            response.newBuilder()
                .body(it.toResponseBody(null))
                .code(200)
                .header(HEADER_FROM_CACHE, "true")
                .build()
        } ?: response
    }

    override fun fetchUserMessages() {
        scope.launch {
            try {
                logger.debug("Fetching user messages")
                val latestMessagesResponse = gistQueueService.fetchMessagesForUser(sessionId = state.sessionId)

                val code = latestMessagesResponse.code()
                val fromCache = latestMessagesResponse.headers()[HEADER_FROM_CACHE] == "true"
                when {
                    (code == 204 || code == 304) -> handleNoContent(code)
                    latestMessagesResponse.isSuccessful -> handleSuccessfulFetch(
                        responseBody = latestMessagesResponse.body(),
                        fromCache = fromCache
                    )

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

    // For cached responses (304), apply locally cached opened status to preserve user's changes.
    // For fresh responses (200), clear cached status and use server's data.
    private fun handleSuccessfulFetch(responseBody: QueueMessagesResponse?, fromCache: Boolean) {
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
            val inboxMessagesMapped = inboxMessages.mapNotNull { item ->
                item.toDomain()?.let { message ->
                    if (fromCache) {
                        // 304: apply cached opened status if available
                        val cachedOpenedStatus = inAppPreferenceStore.getInboxMessageOpenedStatus(message.queueId)
                        return@let cachedOpenedStatus?.let { message.copy(opened = it) } ?: message
                    } else {
                        // 200: clear cached status and use server's data
                        inAppPreferenceStore.clearInboxMessageOpenedStatus(message.queueId)
                        return@let message
                    }
                }
            }
            if (inboxMessagesMapped.size < inboxMessages.size) {
                logger.debug("Filtered out ${inboxMessages.size - inboxMessagesMapped.size} invalid inbox message(s)")
            }
            inAppMessagingManager.dispatch(InAppMessagingAction.ProcessInboxMessages(inboxMessagesMapped))
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
        scope.launch {
            try {
                logger.debug("Updating inbox message ${message.toLogString()} opened status to: $opened")
                // Log updated opened status to server
                gistQueueService.logInboxMessageOpened(
                    queueId = message.queueId,
                    sessionId = state.sessionId,
                    body = mapOf("opened" to opened)
                )
                // Cache the opened status locally for 304 responses
                inAppPreferenceStore.saveInboxMessageOpenedStatus(message.queueId, opened)
            } catch (e: Exception) {
                logger.error("Failed to update inbox message ${message.toLogString()} opened status: ${e.message}")
            }
        }
    }

    override fun logDeleted(message: InboxMessage) {
        scope.launch {
            try {
                logger.debug("Deleting inbox message: ${message.toLogString()}")
                // Log deletion event to server
                gistQueueService.logUserMessageView(
                    queueId = message.queueId,
                    sessionId = state.sessionId
                )
                // Clear any cached opened status for deleted message
                inAppPreferenceStore.clearInboxMessageOpenedStatus(message.queueId)
            } catch (e: Exception) {
                logger.error("Failed to delete inbox message ${message.toLogString()}: ${e.message}")
            }
        }
    }

    companion object {
        // Custom header to distinguish 304 responses (converted to 200) from genuine 200 responses
        private const val HEADER_FROM_CACHE = "X-CIO-MOBILE-SDK-Cache"
    }
}

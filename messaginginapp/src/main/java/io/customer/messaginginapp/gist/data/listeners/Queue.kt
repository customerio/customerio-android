package io.customer.messaginginapp.gist.data.listeners

import android.content.Context
import android.util.Base64
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.di.inAppPreferenceStore
import io.customer.messaginginapp.gist.data.NetworkUtilities
import io.customer.messaginginapp.gist.data.model.Message
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
import retrofit2.http.POST
import retrofit2.http.Path

interface GistQueueService {
    @POST("/api/v2/users")
    suspend fun fetchMessagesForUser(@Body body: Any = Object()): Response<List<Message>>

    @POST("/api/v1/logs/message/{messageId}")
    suspend fun logMessageView(@Path("messageId") messageId: String)

    @POST("/api/v1/logs/queue/{queueId}")
    suspend fun logUserMessageView(@Path("queueId") queueId: String)
}

interface GistQueue {
    fun fetchUserMessages()
    fun logView(message: Message)
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
                    .addHeader(NetworkUtilities.CIO_SITE_ID_HEADER, state.siteId)
                    .addHeader(NetworkUtilities.CIO_DATACENTER_HEADER, state.dataCenter)
                    .addHeader(NetworkUtilities.CIO_CLIENT_PLATFORM, SDKComponent.android().client.source.lowercase())
                    .addHeader(NetworkUtilities.CIO_CLIENT_VERSION, SDKComponent.android().client.sdkVersion)
                    .apply {
                        state.userId?.let { userToken ->
                            addHeader(
                                NetworkUtilities.USER_TOKEN_HEADER,
                                Base64.encodeToString(userToken.toByteArray(), Base64.NO_WRAP)
                            )
                        }
                    }
                    .header("Cache-Control", "no-cache")
                    .build()

                interceptResponse(chain.proceed(networkRequest), originalRequest)
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
                if (state.userId == null) {
                    logger.debug("User not set, skipping fetch")
                    return@launch
                }
                val latestMessagesResponse = gistQueueService.fetchMessagesForUser()

                val code = latestMessagesResponse.code()
                when {
                    (code == 204 || code == 304) -> handleNoContent(code)
                    latestMessagesResponse.isSuccessful -> handleSuccessfulFetch(latestMessagesResponse.body())
                    else -> handleFailedFetch(code)
                }

                updatePollingInterval(latestMessagesResponse.headers())
            } catch (e: Exception) {
                logger.debug("Error fetching messages: ${e.message}")
            }
        }
    }

    private fun handleNoContent(responseCode: Int) {
        logger.debug("No messages found for user with response code: $responseCode")
        inAppMessagingManager.dispatch(InAppMessagingAction.ClearMessageQueue)
    }

    private fun handleSuccessfulFetch(responseBody: List<Message>?) {
        logger.debug("Found ${responseBody?.count()} messages for user")
        responseBody?.let { messages ->
            inAppMessagingManager.dispatch(InAppMessagingAction.ProcessMessageQueue(messages))
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

    override fun logView(message: Message) {
        scope.launch {
            try {
                logger.debug("Logging view for message: $message")
                if (message.queueId != null) {
                    gistQueueService.logUserMessageView(message.queueId)
                } else {
                    gistQueueService.logMessageView(message.messageId)
                }
            } catch (e: Exception) {
                logger.debug("Failed to log message view: ${e.message}")
            }
        }
    }
}

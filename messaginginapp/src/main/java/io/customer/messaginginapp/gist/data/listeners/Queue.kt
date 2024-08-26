package io.customer.messaginginapp.gist.data.listeners

import android.content.Context
import android.util.Base64
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.NetworkUtilities
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.repository.GistQueueService
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

interface GistQueue {
    fun fetchUserMessages()
    fun clearPrefs(context: Context)
    fun logView(message: Message)
}

class Queue : GistQueue {

    private val inAppMessagingManager = SDKComponent.inAppMessagingManager
    private val state: InAppMessagingState
        get() = inAppMessagingManager.getCurrentState()
    private val logger: Logger = SDKComponent.logger
    private val scope: CoroutineScope = SDKComponent.scopeProvider.lifecycleListenerScope
    private val application: Context
        get() = SDKComponent.android().applicationContext

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

                handleResponse(chain.proceed(networkRequest), originalRequest)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(GistEnvironment.PROD.getGistQueueApiUrl())
            .addConverterFactory(GsonConverterFactory.create())
            .client(httpClient)
            .build()
            .create(GistQueueService::class.java)
    }

    private fun handleResponse(response: okhttp3.Response, originalRequest: okhttp3.Request): okhttp3.Response {
        return when (response.code) {
            200 -> handleSuccessfulResponse(response, originalRequest)
            304 -> handleNotModifiedResponse(response, originalRequest)
            else -> response
        }
    }

    private fun handleSuccessfulResponse(response: okhttp3.Response, originalRequest: okhttp3.Request): okhttp3.Response {
        response.body?.let { responseBody ->
            val responseBodyString = responseBody.string()
            saveToPrefs(application, originalRequest.url.toString(), responseBodyString)
            return response.newBuilder()
                .body(responseBodyString.toResponseBody(responseBody.contentType()))
                .build()
        }
        return response
    }

    private fun handleNotModifiedResponse(response: okhttp3.Response, originalRequest: okhttp3.Request): okhttp3.Response {
        val cachedResponse = getFromPrefs(application, originalRequest.url.toString())
        return cachedResponse?.let {
            response.newBuilder()
                .body(it.toResponseBody(null))
                .code(200)
                .build()
        } ?: response
    }

    private fun saveToPrefs(context: Context, key: String, value: String) {
        context.getSharedPreferences("network_cache", Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()
    }

    private fun getFromPrefs(context: Context, key: String): String? {
        return context.getSharedPreferences("network_cache", Context.MODE_PRIVATE)
            .getString(key, null)
    }

    override fun clearPrefs(context: Context) {
        context.getSharedPreferences("network_cache", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    override fun fetchUserMessages() {
        scope.launch {
            try {
                logger.debug("Fetching user messages")
                val latestMessagesResponse = gistQueueService.fetchMessagesForUser()

                when {
                    latestMessagesResponse.code() == 204 -> handleNoContent()
                    latestMessagesResponse.isSuccessful -> handleSuccessfulFetch(latestMessagesResponse.body())
                    else -> handleFailedFetch(latestMessagesResponse.code())
                }

                updatePollingInterval(latestMessagesResponse.headers())
            } catch (e: Exception) {
                logger.debug("Error fetching messages: ${e.message}")
            }
        }
    }

    private fun handleNoContent() {
        logger.debug("No messages found for user")
        inAppMessagingManager.dispatch(InAppMessagingAction.ClearMessageQueue)
    }

    private fun handleSuccessfulFetch(responseBody: List<Message>?) {
        logger.debug("Found ${responseBody?.count()} messages for user")
        responseBody?.let { messages ->
            inAppMessagingManager.dispatch(InAppMessagingAction.ProcessMessageQueue(messages))
        }
    }

    private fun handleFailedFetch(responseCode: Int) {
        logger.debug("Failed to fetch messages: $responseCode")
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

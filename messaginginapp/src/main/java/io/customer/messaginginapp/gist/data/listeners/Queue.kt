package io.customer.messaginginapp.gist.data.listeners

import android.content.Context
import android.util.Base64
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.NetworkUtilities
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.repository.GistQueueService
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
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

    private val inAppMessagingManager: InAppMessagingManager
        get() = SDKComponent.inAppMessagingManager
    private val state: InAppMessagingState
        get() = inAppMessagingManager.getCurrentState()
    private val logger: Logger = SDKComponent.logger
    private val scope: CoroutineScope = SDKComponent.scopeProvider.lifecycleListenerScope
    private val application: Context
        get() = SDKComponent.android().applicationContext

    private val cacheSize = 10 * 1024 * 1024 // 10 MB
    private val cacheDirectory by lazy { File(application.cacheDir, "http_cache") }
    private val cache by lazy { Cache(cacheDirectory, cacheSize.toLong()) }

    private fun saveToPrefs(context: Context, key: String, value: String) {
        val prefs = context.getSharedPreferences("network_cache", Context.MODE_PRIVATE)
        prefs.edit().putString(key, value).apply()
    }

    private fun getFromPrefs(context: Context, key: String): String? {
        val prefs = context.getSharedPreferences("network_cache", Context.MODE_PRIVATE)
        return prefs.getString(key, null)
    }

    override fun clearPrefs(context: Context) {
        context.getSharedPreferences("network_cache", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private val gistQueueService by lazy {
        // Interceptor to set up request headers like site ID, data center, and user token.
        val httpClient: OkHttpClient = OkHttpClient.Builder().cache(cache).addInterceptor { chain ->
            val originalRequest = chain.request()

            val networkRequest = originalRequest.newBuilder().addHeader(NetworkUtilities.CIO_SITE_ID_HEADER, state.siteId).addHeader(NetworkUtilities.CIO_DATACENTER_HEADER, state.dataCenter).apply {
                state.userId?.let { userToken ->
                    addHeader(
                        NetworkUtilities.USER_TOKEN_HEADER,
                        // The NO_WRAP flag will omit all line terminators (i.e., the output will be on one long line).
                        Base64.encodeToString(userToken.toByteArray(), Base64.NO_WRAP)
                    )
                }
            }.header("Cache-Control", "no-cache").build()

            val response = chain.proceed(networkRequest)

            when (response.code) {
                200 -> {
                    response.body?.let { responseBody ->
                        val responseBodyString = responseBody.string()
                        saveToPrefs(
                            application,
                            originalRequest.url.toString(),
                            responseBodyString
                        )
                        return@addInterceptor response.newBuilder().body(
                            responseBodyString.toResponseBody(responseBody.contentType())
                        ).build()
                    }
                }

                304 -> {
                    val cachedResponse = getFromPrefs(application, originalRequest.url.toString())
                    cachedResponse?.let {
                        return@addInterceptor response.newBuilder().body(it.toResponseBody(null)).code(200).build()
                    } ?: return@addInterceptor response
                }

                else -> return@addInterceptor response
            }

            response
        }.build()

        Retrofit.Builder().baseUrl(GistEnvironment.PROD.getGistQueueApiUrl()).addConverterFactory(GsonConverterFactory.create()).client(httpClient).build().create(GistQueueService::class.java)
    }

    override fun fetchUserMessages() {
        scope.launch {
            try {
                logger.debug("Fetching user messages")
                val latestMessagesResponse = gistQueueService.fetchMessagesForUser()

                val responseBody = latestMessagesResponse.body()

                if (latestMessagesResponse.code() == 204) {
                    // No content, don't do anything
                    logger.debug("No messages found for user")
                    // To prevent us from showing expired / revoked messages, clear user messages from local queue.
                    inAppMessagingManager.dispatch(InAppMessagingAction.ClearMessageQueue)
                } else if (latestMessagesResponse.isSuccessful) {
                    logger.debug("Found ${responseBody?.count()} messages for user")
                    responseBody?.let { messages ->
                        // If the messages in the local queue the same as the number of messages fetched, don't update the queue.
                        inAppMessagingManager.dispatch(InAppMessagingAction.ProcessMessageQueue(messages))
                    }
                } else {
                    logger.debug("Failed to fetch messages: ${latestMessagesResponse.code()}")
                    // To prevent us from showing expired / revoked messages, clear user messages from local queue.
                    inAppMessagingManager.dispatch(InAppMessagingAction.ClearMessageQueue)
                }

                // Check if the polling interval changed and update timer.
                updatePollingInterval(latestMessagesResponse.headers())
            } catch (e: Exception) {
                logger.debug("Error fetching messages: ${e.message}")
            }
        }
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

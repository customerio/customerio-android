package io.customer.messaginginapp.gist.data.listeners

import android.content.Context
import android.util.Base64
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.domain.InAppMessagingAction
import io.customer.messaginginapp.domain.InAppMessagingManager
import io.customer.messaginginapp.gist.data.NetworkUtilities
import io.customer.messaginginapp.gist.data.model.GistMessageProperties
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.repository.GistQueueService
import io.customer.messaginginapp.gist.presentation.GistListener
import io.customer.messaginginapp.gist.presentation.GistSdk
import io.customer.sdk.core.di.SDKComponent
import java.io.File
import java.util.regex.PatternSyntaxException
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class Queue : GistListener {

    internal var localMessageStore: MutableList<Message> = mutableListOf()
    internal var shownMessageQueueIds = mutableSetOf<String>()
    private val inAppMessagingManager: InAppMessagingManager = SDKComponent.inAppMessagingManager

    init {
        GistSdk.addListener(this)
    }

    private val cacheSize = 10 * 1024 * 1024 // 10 MB
    private val cacheDirectory by lazy { File(GistSdk.application.cacheDir, "http_cache") }
    private val cache by lazy { Cache(cacheDirectory, cacheSize.toLong()) }

    private fun saveToPrefs(context: Context, key: String, value: String) {
        val prefs = context.getSharedPreferences("network_cache", Context.MODE_PRIVATE)
        prefs.edit().putString(key, value).apply()
    }

    private fun getFromPrefs(context: Context, key: String): String? {
        val prefs = context.getSharedPreferences("network_cache", Context.MODE_PRIVATE)
        return prefs.getString(key, null)
    }

    internal fun clearPrefs(context: Context) {
        context.getSharedPreferences("network_cache", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private val gistQueueService by lazy {
        // Interceptor to set up request headers like site ID, data center, and user token.
        val httpClient: OkHttpClient =
            OkHttpClient.Builder().cache(cache)
                .addInterceptor { chain ->
                    val originalRequest = chain.request()

                    val networkRequest = originalRequest.newBuilder()
                        .addHeader(NetworkUtilities.CIO_SITE_ID_HEADER, GistSdk.siteId)
                        .addHeader(NetworkUtilities.CIO_DATACENTER_HEADER, GistSdk.dataCenter)
                        .apply {
                            GistSdk.getUserToken()?.let { userToken ->
                                addHeader(
                                    NetworkUtilities.USER_TOKEN_HEADER,
                                    // The NO_WRAP flag will omit all line terminators (i.e., the output will be on one long line).
                                    Base64.encodeToString(userToken.toByteArray(), Base64.NO_WRAP)
                                )
                            }
                        }
                        .header("Cache-Control", "no-cache")
                        .build()

                    val response = chain.proceed(networkRequest)

                    when (response.code) {
                        200 -> {
                            response.body?.let { responseBody ->
                                val responseBodyString = responseBody.string()
                                saveToPrefs(
                                    GistSdk.application,
                                    originalRequest.url.toString(),
                                    responseBodyString
                                )
                                return@addInterceptor response.newBuilder().body(
                                    responseBodyString.toResponseBody(responseBody.contentType())
                                ).build()
                            }
                        }

                        304 -> {
                            val cachedResponse =
                                getFromPrefs(GistSdk.application, originalRequest.url.toString())
                            cachedResponse?.let {
                                return@addInterceptor response.newBuilder()
                                    .body(it.toResponseBody(null)).code(200).build()
                            } ?: return@addInterceptor response
                        }

                        else -> return@addInterceptor response
                    }

                    response
                }
                .build()

        Retrofit.Builder()
            .baseUrl(GistSdk.gistEnvironment.getGistQueueApiUrl())
            .addConverterFactory(GsonConverterFactory.create())
            .client(httpClient)
            .build()
            .create(GistQueueService::class.java)
    }

    internal fun fetchUserMessagesFromLocalStore() {
        handleMessages(localMessageStore)
    }

    internal fun clearUserMessagesFromLocalStore() {
        localMessageStore.clear()
    }

    internal fun fetchUserMessages() {
        GistSdk.coroutineScope.launch {
            try {
                inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Fetching user messages"))
                val latestMessagesResponse = gistQueueService.fetchMessagesForUser()

                val responseBody = latestMessagesResponse.body()

                responseBody?.let { messages ->
                    inAppMessagingManager.dispatch(InAppMessagingAction.UpdateMessages(previous = localMessageStore, new = messages))
                }

                // To prevent us from showing expired / revoked messages, clear user messages from local queue.
                clearUserMessagesFromLocalStore()
                if (latestMessagesResponse.code() == 204) {
                    // No content, don't do anything
                    inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("No messages found for user"))
                } else if (latestMessagesResponse.isSuccessful) {
                    inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Found ${responseBody?.count()} messages for user"))
                    responseBody?.let { messages ->
                        handleMessages(messages) { message ->
                            addMessageToLocalStore(message)
                        }
                    }
                }

                // Check if the polling interval changed and update timer.
                updatePollingInterval(latestMessagesResponse.headers())
            } catch (e: Exception) {
                inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Error fetching messages: ${e.message}"))
            }
        }
    }

    private fun updatePollingInterval(headers: Headers) {
        headers["X-Gist-Queue-Polling-Interval"]?.toIntOrNull()?.let { pollingIntervalSeconds ->
            if (pollingIntervalSeconds > 0) {
                val newPollingIntervalMilliseconds = (pollingIntervalSeconds * 1000).toLong()
                if (newPollingIntervalMilliseconds != GistSdk.pollInterval) {
                    GistSdk.pollInterval = newPollingIntervalMilliseconds
                    // Queue check fetches messages again and could result in infinite loop.
                    GistSdk.observeMessagesForUser(true)
                    inAppMessagingManager.dispatch(InAppMessagingAction.PollingInterval(newPollingIntervalMilliseconds))
                    inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Polling interval changed to: $pollingIntervalSeconds seconds"))
                }
            }
        }
    }

    /**
     * Handles messages by sorting them by priority and placing nulls last.
     *
     * @param messages List of messages to handle
     * @param preProcessMessageAction Action to perform before processing each message, e.g. adding to local store, etc.
     */
    @Synchronized
    private fun handleMessages(messages: List<Message>, preProcessMessageAction: (Message) -> Unit = {}) {
        // Sorting messages by priority and placing nulls last
        val sortedMessages = messages.sortedWith(compareBy(nullsLast()) { it.priority })
        for (message in sortedMessages) {
            preProcessMessageAction(message)
            processMessage(message)
        }
    }

    private fun processMessage(message: Message) {
        inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Processing message: ${message.messageId}"))

        if (message.queueId != null && shownMessageQueueIds.contains(message.queueId)) {
            inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Duplicate message $message skipped"))
            return
        }

        val gistProperties = GistMessageProperties.getGistProperties(message)
        inAppMessagingManager.dispatch(InAppMessagingAction.ProcessMessage(message, gistProperties))
        gistProperties.routeRule?.let { routeRule ->
            try {
                if (!routeRule.toRegex().matches(GistSdk.currentRoute)) {
                    inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Message $message skipped due to route rule : ${GistSdk.currentRoute}"))
                    return
                }
            } catch (e: PatternSyntaxException) {
                inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Invalid route rule regex: $routeRule"))
                return
            }
        }
        gistProperties.elementId?.let { elementId ->
            inAppMessagingManager.dispatch(InAppMessagingAction.EmbedMessage(message, elementId))
            GistSdk.handleEmbedMessage(message, elementId)
        } ?: run {
            inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Showing message from queue with queue: $message"))
            inAppMessagingManager.dispatch(InAppMessagingAction.ShowModal(message))
            GistSdk.showMessage(message)
        }
    }

    internal fun logView(message: Message) {
        GistSdk.coroutineScope.launch {
            try {
                inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Logging view for message: $message"))
                if (message.queueId != null) {
                    shownMessageQueueIds.add(message.queueId)
                    removeMessageFromLocalStore(message)
                    gistQueueService.logUserMessageView(message.queueId)
                } else {
                    gistQueueService.logMessageView(message.messageId)
                }
            } catch (e: Exception) {
                inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Failed to log message view: ${e.message}"))
            }
        }
    }

    private fun addMessageToLocalStore(message: Message) {
        val localMessage =
            localMessageStore.find { localMessage -> localMessage.queueId == message.queueId }
        if (localMessage == null) {
            inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Adding message to local store: $message"))
            localMessageStore.add(message)
        }
    }

    private fun removeMessageFromLocalStore(message: Message) {
        localMessageStore.removeAll { it.queueId == message.queueId }
    }

    override fun onMessageShown(message: Message) {
        val gistProperties = GistMessageProperties.getGistProperties(message)
        if (gistProperties.persistent) {
            inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Persistent message shown: $message, skipping logging view"))
        } else {
            logView(message)
        }
    }

    override fun embedMessage(message: Message, elementId: String) {}

    override fun onMessageDismissed(message: Message) {}

    override fun onMessageCancelled(message: Message) {}

    override fun onError(message: Message) {}

    override fun onAction(message: Message, currentRoute: String, action: String, name: String) {}
}

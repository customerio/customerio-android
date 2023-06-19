package io.customer.messaginginapp.gist.data.listeners

import android.util.Base64
import android.util.Log
import io.customer.messaginginapp.gist.data.NetworkUtilities
import io.customer.messaginginapp.gist.data.model.GistMessageProperties
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.repository.GistQueueService
import io.customer.messaginginapp.gist.presentation.GIST_TAG
import io.customer.messaginginapp.gist.presentation.GistListener
import io.customer.messaginginapp.gist.presentation.GistSdk
import java.util.regex.PatternSyntaxException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class Queue : GistListener {

    private var localMessageStore: MutableList<Message> = mutableListOf()

    init {
        GistSdk.addListener(this)
    }

    private val gistQueueService by lazy {
        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                GistSdk.getUserToken()?.let { userToken ->
                    val request: Request = chain.request().newBuilder()
                        .addHeader(NetworkUtilities.CIO_SITE_ID_HEADER, GistSdk.siteId)
                        .addHeader(NetworkUtilities.CIO_DATACENTER_HEADER, GistSdk.dataCenter)
                        .addHeader(
                            NetworkUtilities.USER_TOKEN_HEADER,
                            // The NO_WRAP flag will omit all line terminators (i.e., the output will be on one long line).
                            Base64.encodeToString(userToken.toByteArray(), Base64.NO_WRAP)
                        )
                        .build()

                    chain.proceed(request)
                } ?: run {
                    val request: Request = chain.request().newBuilder()
                        .addHeader(NetworkUtilities.CIO_SITE_ID_HEADER, GistSdk.siteId)
                        .addHeader(NetworkUtilities.CIO_DATACENTER_HEADER, GistSdk.dataCenter)
                        .build()

                    chain.proceed(request)
                }
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
        GlobalScope.launch {
            try {
                Log.i(GIST_TAG, "Fetching user messages")
                val latestMessagesResponse = gistQueueService.fetchMessagesForUser()
                // If there's no change (304), move on.
                if (latestMessagesResponse.code() == 304) { return@launch }

                // To prevent us from showing expired / revoked messages, clear user messages from local queue.
                clearUserMessagesFromLocalStore()
                if (latestMessagesResponse.code() == 204) {
                    // No content, don't do anything
                    Log.i(GIST_TAG, "No messages found for user")
                } else if (latestMessagesResponse.isSuccessful) {
                    Log.i(
                        GIST_TAG,
                        "Found ${latestMessagesResponse.body()?.count()} messages for user"
                    )
                    latestMessagesResponse.body()?.let { handleMessages(it) }
                }
            } catch (e: Exception) {
                Log.e(
                    GIST_TAG,
                    "Error fetching messages: ${e.message}"
                )
            }
        }
    }

    private fun handleMessages(messages: List<Message>) {
        run loop@{
            messages.forEach foreach@{ message ->
                val gistProperties = GistMessageProperties.getGistProperties(message)
                gistProperties.routeRule?.let { routeRule ->
                    try {
                        if (!routeRule.toRegex().matches(GistSdk.currentRoute)) {
                            Log.i(
                                GIST_TAG,
                                "Message route: $routeRule does not match current route: ${GistSdk.currentRoute}"
                            )
                            addMessageToLocalStore(message)
                            return@foreach
                        }
                    } catch (e: PatternSyntaxException) {
                        Log.i(GIST_TAG, "Invalid route rule regex: $routeRule")
                        return@foreach
                    }
                }
                gistProperties.elementId?.let { elementId ->
                    Log.i(
                        GIST_TAG,
                        "Embedding message from queue with queue id: ${message.queueId}"
                    )
                    GistSdk.handleEmbedMessage(message, elementId)
                } ?: run {
                    Log.i(
                        GIST_TAG,
                        "Showing message from queue with queue id: ${message.queueId}"
                    )
                    GistSdk.showMessage(message)
                    return@loop
                }
            }
        }
    }

    internal fun logView(message: Message) {
        GlobalScope.launch {
            try {
                if (message.queueId != null) {
                    Log.i(
                        GIST_TAG,
                        "Logging view for user message: ${message.messageId}, with queue id: ${message.queueId}"
                    )
                    removeMessageFromLocalStore(message)
                    gistQueueService.logUserMessageView(message.queueId)
                } else {
                    Log.i(GIST_TAG, "Logging view for message: ${message.messageId}")
                    gistQueueService.logMessageView(message.messageId)
                }
            } catch (e: Exception) {
                Log.e(GIST_TAG, "Failed to log message view: ${e.message}", e)
            }
        }
    }

    private fun addMessageToLocalStore(message: Message) {
        val localMessage =
            localMessageStore.find { localMessage -> localMessage.queueId == message.queueId }
        if (localMessage == null) {
            localMessageStore.add(message)
        }
    }

    private fun removeMessageFromLocalStore(message: Message) {
        localMessageStore.removeAll { it.queueId == message.queueId }
    }

    override fun onMessageShown(message: Message) {
        val gistProperties = GistMessageProperties.getGistProperties(message)
        if (gistProperties.persistent) {
            Log.i(GIST_TAG, "Persistent message shown: ${message.messageId}, skipping logging view")
        } else {
            logView(message)
        }
    }

    override fun embedMessage(message: Message, elementId: String) {}

    override fun onMessageDismissed(message: Message) {}

    override fun onError(message: Message) {}

    override fun onAction(message: Message, currentRoute: String, action: String, name: String) {}
}

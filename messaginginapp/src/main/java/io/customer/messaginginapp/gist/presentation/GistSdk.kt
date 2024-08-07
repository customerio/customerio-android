package io.customer.messaginginapp.gist.presentation

import android.app.Activity
import android.app.Application
import android.content.Context.MODE_PRIVATE
import android.util.Log
import androidx.lifecycle.Lifecycle
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.listeners.Queue
import io.customer.messaginginapp.gist.data.model.GistMessageProperties
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.messaginginapp.gist.presentation.engine.EngineWebViewClientInterceptor
import io.customer.sdk.core.di.SDKComponent
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ticker
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

// replace with: CustomerIOLogger
const val GIST_TAG: String = "[CIO]"

object GistSdk {
    private const val SHARED_PREFERENCES_NAME = "gist-sdk"
    private const val SHARED_PREFERENCES_USER_TOKEN_KEY = "userToken"

    private val sharedPreferences by lazy {
        application.getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE)
    }

    internal var pollInterval = 600_000L
    lateinit var siteId: String
    lateinit var dataCenter: String
    internal lateinit var gistEnvironment: GistEnvironment
    internal lateinit var application: Application

    private val listeners: CopyOnWriteArrayList<GistListener> = CopyOnWriteArrayList()

    private var resumedActivities = mutableSetOf<String>()

    private var observeUserMessagesJob: Job? = null
    private var isInitialized = false

    internal var gistQueue: Queue = Queue()
    internal var gistModalManager: GistModalManager = GistModalManager()
    internal var currentRoute: String = ""

    // Global CoroutineScope for Gist Engine, all Gist classes should use same scope
    internal var coroutineScope: CoroutineScope = GlobalScope

    // Global WebViewClientInterceptor for Gist Engine, used to intercept WebViewClient events while testing
    internal var engineWebViewClientInterceptor: EngineWebViewClientInterceptor? = null

    @JvmStatic
    fun getInstance() = this

    internal fun onActivityResumed(activity: Activity) {
        resumedActivities.add(activity.javaClass.name)

        // Start polling if app is resumed and user messages are not being observed
        val isNotObservingMessages =
            observeUserMessagesJob == null || observeUserMessagesJob?.isCancelled == true

        if (isAppResumed() && getUserToken() != null && isNotObservingMessages) {
            observeMessagesForUser()
        }
    }

    internal fun onActivityPaused(activity: Activity) {
        resumedActivities.remove(activity.javaClass.name)

        // Stop polling if app is in background
        if (!isAppResumed()) {
            observeUserMessagesJob?.cancel()
            observeUserMessagesJob = null
        }
    }

    fun init(
        application: Application,
        siteId: String,
        dataCenter: String,
        environment: GistEnvironment = GistEnvironment.PROD
    ) {
        GistSdk.application = application
        GistSdk.siteId = siteId
        GistSdk.dataCenter = dataCenter
        isInitialized = true
        gistEnvironment = environment

        subscribeToLifecycleEvents()

        coroutineScope.launch {
            try {
                // Observe user messages if user token is set
                if (getUserToken() != null) {
                    observeMessagesForUser()
                }
            } catch (e: Exception) {
                Log.e(GIST_TAG, e.message, e)
            }
        }
    }

    /**
     * Reset the Gist SDK to its initial state.
     * This method is used for testing purposes only.
     */
    internal fun reset() {
        isInitialized = false
        observeUserMessagesJob?.cancel()
        observeUserMessagesJob = null
        gistQueue.clearUserMessagesFromLocalStore()
        gistModalManager.clearCurrentMessage()
        resumedActivities.clear()
        listeners.clear()

        gistQueue = Queue()
        gistModalManager = GistModalManager()
        currentRoute = ""
    }

    private fun subscribeToLifecycleEvents() {
        SDKComponent.activityLifecycleCallbacks.subscribe { events ->
            events
                .filter { state ->
                    state.event == Lifecycle.Event.ON_RESUME || state.event == Lifecycle.Event.ON_PAUSE
                }.collect { state ->
                    val activity = state.activity.get() ?: return@collect
                    when (state.event) {
                        Lifecycle.Event.ON_RESUME -> onActivityResumed(activity)
                        Lifecycle.Event.ON_PAUSE -> onActivityPaused(activity)
                        else -> {}
                    }
                }
        }
    }

    fun setCurrentRoute(route: String) {
        if (currentRoute == route) {
            Log.i(GIST_TAG, "Current gist route is already set to: $currentRoute, ignoring new route")
            return
        }

        cancelActiveMessage(newRoute = route)
        currentRoute = route
        Log.i(GIST_TAG, "Current gist route set to: $currentRoute")
        gistQueue.fetchUserMessagesFromLocalStore()
    }

    /**
     * Cancels any active message being loaded if the page rule does not match the new route.
     */
    private fun cancelActiveMessage(newRoute: String) {
        val currentMessage = gistModalManager.currentMessage
        val isRouteMatch = runCatching {
            val routeRule = currentMessage?.let { message ->
                GistMessageProperties.getGistProperties(message).routeRule
            }
            routeRule == null || routeRule.toRegex().matches(newRoute)
        }.getOrNull() ?: true

        // If there is no active message or the message does not have a route rule, we don't need to do anything
        if (currentMessage == null || isRouteMatch) {
            return
        }

        Log.i(GIST_TAG, "Cancelling message being loaded: ${currentMessage.messageId}")
        handleGistCancelled(currentMessage)
    }

    // User Token

    fun clearUserToken() {
        ensureInitialized()
        cancelActiveMessage(newRoute = "")
        gistQueue.clearUserMessagesFromLocalStore()
        gistQueue.clearPrefs(application)
        // Remove user token from preferences & cancel job / timer.
        sharedPreferences.edit().remove(SHARED_PREFERENCES_USER_TOKEN_KEY).apply()
        observeUserMessagesJob?.cancel()
    }

    fun setUserToken(userToken: String) {
        ensureInitialized()

        if (!getUserToken().equals(userToken)) {
            Log.i(GIST_TAG, "Setting user token to: $userToken")
            // Save user token in preferences to be fetched on the next launch
            sharedPreferences.edit().putString(SHARED_PREFERENCES_USER_TOKEN_KEY, userToken).apply()

            // Try to observe messages for the freshly set user token
            try {
                observeMessagesForUser()
            } catch (e: Exception) {
                Log.e(GIST_TAG, "Failed to observe messages for user: ${e.message}", e)
            }
        }
    }

    // Messages

    fun showMessage(message: Message, position: MessagePosition? = null): String? {
        ensureInitialized()
        var messageShown = false

        coroutineScope.launch {
            try {
                messageShown = gistModalManager.showModalMessage(message, position)
            } catch (e: Exception) {
                Log.e(GIST_TAG, "Failed to show message: ${e.message}", e)
            }
        }

        return if (messageShown) message.instanceId else null
    }

    fun dismissMessage() {
        gistModalManager.dismissActiveMessage()
    }

    fun clearCurrentMessage() {
        gistModalManager.clearCurrentMessage()
    }

    // Listeners

    fun addListener(listener: GistListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: GistListener) {
        listeners.remove(listener)
    }

    fun clearListeners() {
        for (listener in listeners) {
            val listenerPackageName = listener.javaClass.`package`?.name
            if (!listenerPackageName.toString().startsWith("build.gist.")) {
                Log.d(GIST_TAG, "Removing listener $listenerPackageName")
                listeners.remove(listener)
            }
        }
    }

    // Gist Message Observer

    internal fun observeMessagesForUser(skipQueueCheck: Boolean = false) {
        // Clean up any previous observers
        observeUserMessagesJob?.cancel()

        Log.i(GIST_TAG, "Messages timer started")
        if (!skipQueueCheck) {
            gistQueue.fetchUserMessages()
        }
        observeUserMessagesJob = coroutineScope.launch {
            try {
                // Poll for user messages
                val ticker = ticker(pollInterval, context = this.coroutineContext)
                for (tick in ticker) {
                    gistQueue.fetchUserMessages()
                }
            } catch (e: CancellationException) {
                // Co-routine was cancelled, cancel internal timer
                Log.i(GIST_TAG, "Messages timer cancelled")
            } catch (e: Exception) {
                Log.e(GIST_TAG, "Failed to get user messages: ${e.message}", e)
            }
        }
    }

    internal fun dismissPersistentMessage(message: Message) {
        val gistProperties = GistMessageProperties.getGistProperties(message)
        if (gistProperties.persistent) {
            Log.i(GIST_TAG, "Persistent message dismissed, logging view")
            gistQueue.logView(message)
        }
        handleGistClosed(message)
    }

    internal fun handleGistLoaded(message: Message) {
        for (listener in listeners) {
            listener.onMessageShown(message)
        }
    }

    internal fun handleGistClosed(message: Message) {
        for (listener in listeners) {
            listener.onMessageDismissed(message)
        }
    }

    private fun handleGistCancelled(message: Message) {
        for (listener in listeners) {
            listener.onMessageCancelled(message)
        }
    }

    internal fun handleGistError(message: Message) {
        for (listener in listeners) {
            listener.onError(message)
        }
    }

    internal fun handleEmbedMessage(message: Message, elementId: String) {
        for (listener in listeners) {
            listener.embedMessage(message, elementId)
        }
    }

    internal fun handleGistAction(
        message: Message,
        currentRoute: String,
        action: String,
        name: String
    ) {
        for (listener in listeners) {
            listener.onAction(message, currentRoute, action, name)
        }
    }

    internal fun getUserToken(): String? {
        return sharedPreferences.getString(SHARED_PREFERENCES_USER_TOKEN_KEY, null)
    }

    private fun ensureInitialized() {
        if (!isInitialized) throw IllegalStateException("GistSdk must be initialized by calling GistSdk.init()")
    }

    private fun isAppResumed() = resumedActivities.isNotEmpty()
}

interface GistListener {
    fun embedMessage(message: Message, elementId: String)
    fun onMessageShown(message: Message)
    fun onMessageDismissed(message: Message)
    fun onMessageCancelled(message: Message)
    fun onError(message: Message)
    fun onAction(message: Message, currentRoute: String, action: String, name: String)
}

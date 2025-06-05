package io.customer.messaginginapp.ui.controller

import android.content.ActivityNotFoundException
import androidx.annotation.CallSuper
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.engine.EngineWebConfiguration
import io.customer.messaginginapp.gist.presentation.engine.EngineWebViewListener
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.type.InAppMessage
import io.customer.messaginginapp.type.InlineMessageActionListener
import io.customer.messaginginapp.ui.bridge.EngineWebViewDelegate
import io.customer.messaginginapp.ui.bridge.InAppHostViewDelegate
import io.customer.messaginginapp.ui.bridge.InAppMessageViewCallback
import io.customer.messaginginapp.ui.bridge.InAppPlatformDelegate
import io.customer.sdk.core.di.SDKComponent

/**
 * Base controller class for managing in-app message view behavior.
 * Encapsulates logic for view state transitions, sizing, lifecycle, and WebView interaction.
 * Designed to decouple business logic from Android view classes for better testability and reuse.
 */
internal abstract class InAppMessageViewController<ViewCallback : InAppMessageViewCallback>(
    protected val type: String,
    protected val platformDelegate: InAppPlatformDelegate,
    val viewDelegate: InAppHostViewDelegate
) : ThreadSafeController(), EngineWebViewListener {
    private val logger = SDKComponent.logger
    protected val inAppMessagingManager = SDKComponent.inAppMessagingManager
    protected var shouldDispatchDisplayEvent: Boolean = true

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    var engineWebViewDelegate: EngineWebViewDelegate? by threadSafe()

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @ThreadSafeProperty("Accessed from UI thread writes and background WebView callbacks")
    var currentMessage: Message? by threadSafe()

    @ThreadSafeProperty("Accessed from UI and background threads during route changes")
    var currentRoute: String? by threadSafe()

    var viewCallback: ViewCallback? by threadSafe()

    /**
     * Listener to handle action clicks from inline in-app messages.
     * Set this property to receive callbacks when actions are triggered.
     */
    var actionListener: InlineMessageActionListener? = null

    protected fun logViewEvent(message: String) {
        logger.debug("[InApp][$type] $message")
    }

    /**
     * Attaches a new EngineWebView instance to the view hierarchy if not already attached.
     * Initializes the delegate, sets its alpha and listener, and adds it to the view.
     * @return true if a new EngineWebView was attached, false if already attached.
     */
    @UiThread
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    internal open fun attachEngineWebView(): Boolean {
        logViewEvent("Attaching EngineWebView")
        if (engineWebViewDelegate != null) {
            logViewEvent("EngineWebView already attached, skipping")
            return false
        }

        engineWebViewDelegate = viewDelegate.createEngineWebViewInstance().also { engine ->
            engine.setAlpha(0.0f)
            engine.listener = this
            viewDelegate.addView(engine)
        }
        return true
    }

    /**
     * Detaches and cleans up currently attached EngineWebView instance, if present.
     * Clears listeners, removes the view from hierarchy, and sets the delegate to null.
     * @return true if an EngineWebView was detached, false if already detached.
     */
    @UiThread
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    internal open fun detachEngineWebView(): Boolean {
        logViewEvent("Detaching EngineWebView")
        val delegate = engineWebViewDelegate
        if (delegate == null) {
            logViewEvent("EngineWebView already detached, skipping")
            return false
        }

        engineWebViewDelegate = null
        delegate.listener = null
        viewDelegate.removeView(delegate)
        return true
    }

    @UiThread
    internal fun loadMessage(message: Message) {
        val store = inAppMessagingManager.getCurrentState()
        val config = EngineWebConfiguration(
            siteId = store.siteId,
            dataCenter = store.dataCenter,
            messageId = message.messageId,
            instanceId = message.instanceId,
            endpoint = store.environment.getEngineApiUrl(),
            properties = message.properties
        )

        currentMessage = message
        engineWebViewDelegate?.setup(config)
    }

    @UiThread
    internal fun stopLoading() {
        logViewEvent("Stopping EngineWebView loading")
        stopEngineWebViewLoading()
    }

    protected fun stopEngineWebViewLoading() {
        engineWebViewDelegate?.stopLoading()
    }

    private fun handleTap(name: String, action: String, system: Boolean) {
        val message = currentMessage ?: return
        val route = currentRoute ?: return

        var shouldLogAction = true

        // Dispatch the tap event to track metric and send a global callback
        inAppMessagingManager.dispatch(
            InAppMessagingAction.EngineAction.Tap(
                message = message,
                route = route,
                name = name,
                action = action
            )
        )

        // Check if we have a listener to handle the action
        actionListener?.let { listener ->
            val inAppMessage = InAppMessage.getFromGistMessage(message)
            logViewEvent("Listener handling action: $action, name: $name")
            listener.onActionClick(
                message = inAppMessage,
                actionValue = action,
                actionName = name
            )
        }

        when {
            action.startsWith("gist://") -> {
                val gistAction = platformDelegate.parseJavaURI(action)
                val urlQuery = platformDelegate.sanitizeUrlQuery(action)
                when (gistAction.host) {
                    "close" -> {
                        shouldLogAction = false
                        logViewEvent("Dismissing from action: $action")
                        inAppMessagingManager.dispatch(
                            InAppMessagingAction.DismissMessage(
                                message = message,
                                viaCloseAction = true
                            )
                        )
                    }

                    "loadPage" -> {
                        val url = urlQuery.getValue("url")
                        logViewEvent("Opening URL: $url")
                        platformDelegate.openUrl(url = url, useLaunchFlags = false)
                    }

                    "showMessage" -> {
                        inAppMessagingManager.dispatch(
                            InAppMessagingAction.DismissMessage(
                                message = message,
                                shouldLog = false
                            )
                        )
                        val messageId = urlQuery.getValue("messageId")
                        val propertiesBase64 = urlQuery.getValue("properties")
                        val properties = platformDelegate.parsePropertiesFromJson(propertiesBase64)
                        logViewEvent("Showing message: $messageId")
                        inAppMessagingManager.dispatch(
                            InAppMessagingAction.LoadMessage(
                                Message(
                                    messageId = messageId,
                                    properties = properties
                                )
                            )
                        )
                    }

                    else -> {
                        shouldLogAction = false
                        logViewEvent("Action unhandled: $action")
                    }
                }
            }

            system -> {
                try {
                    shouldLogAction = false
                    logViewEvent("Dismissing from system action: $action")
                    platformDelegate.openUrl(url = action, useLaunchFlags = true)

                    // launch system action first otherwise there is a possibility
                    // that due to lifecycle change and message still being in queue to be displayed
                    // the message will be displayed again, putting GistActivity before the system action in stack
                    inAppMessagingManager.dispatch(
                        InAppMessagingAction.DismissMessage(
                            message = message,
                            shouldLog = false
                        )
                    )
                } catch (e: ActivityNotFoundException) {
                    logViewEvent("System action not handled: $action")
                }
            }
        }

        if (shouldLogAction) {
            logViewEvent("Action selected: $action")
        }
    }

    override fun tap(name: String, action: String, system: Boolean) {
        try {
            handleTap(name, action, system)
        } catch (ex: Exception) {
            logViewEvent("Error handling tap: ${ex.message}")
        }
    }

    override fun routeError(route: String) {
        logViewEvent("Route error: $route")
        currentMessage?.let { message ->
            inAppMessagingManager.dispatch(
                InAppMessagingAction.EngineAction.MessageLoadingFailed(message)
            )
        }
    }

    final override fun routeLoaded(route: String) {
        logViewEvent("Route loaded: $route")
        currentRoute = route
        val message = currentMessage ?: return
        if (!shouldDispatchDisplayEvent) return

        // Dispatch the display event to track metrics
        // The display event should be dispatched only once for each message
        shouldDispatchDisplayEvent = false
        onRouteLoaded(message = message, route = route)
    }

    @CallSuper
    protected open fun onRouteLoaded(message: Message, route: String) {
        inAppMessagingManager.dispatch(InAppMessagingAction.DisplayMessage(message))
    }

    override fun error() {
        logViewEvent("Error loading engine for message: ${currentMessage?.messageId} on route: $currentRoute")
        currentMessage?.let { message ->
            inAppMessagingManager.dispatch(
                InAppMessagingAction.EngineAction.MessageLoadingFailed(message)
            )
        }
    }

    override fun bootstrapped() {
        logViewEvent("Engine bootstrapped")
    }

    override fun routeChanged(newRoute: String) {
        logViewEvent("Route changed: $newRoute")
    }

    override fun sizeChanged(width: Double, height: Double) {
        logViewEvent("Size changed: $width x $height")
        val widthInPx = platformDelegate.convertDpToPixels(width)
        val heightInPx = platformDelegate.convertDpToPixels(height)
        onWebViewSizeUpdated(widthInPx, heightInPx)
    }

    protected open fun onWebViewSizeUpdated(widthInPx: Int, heightInPx: Int) {
        viewCallback?.onViewSizeChanged(widthInPx, heightInPx)
    }
}

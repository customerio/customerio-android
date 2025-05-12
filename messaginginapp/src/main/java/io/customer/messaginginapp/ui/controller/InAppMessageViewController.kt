package io.customer.messaginginapp.ui.controller

import android.content.ActivityNotFoundException
import androidx.annotation.UiThread
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
    protected val viewDelegate: InAppHostViewDelegate
) : EngineWebViewListener {
    internal val logger = SDKComponent.logger
    internal val inAppMessagingManager = SDKComponent.inAppMessagingManager

    internal var engineWebViewDelegate: EngineWebViewDelegate? = null
    internal var currentMessage: Message? = null
    internal var currentRoute: String? = null
    internal var viewCallback: ViewCallback? = null

    /**
     * Listener to handle action clicks from inline in-app messages.
     * Set this property to receive callbacks when actions are triggered.
     */
    internal var actionListener: InlineMessageActionListener? = null

    internal fun logViewEvent(message: String) {
        logger.debug("[InApp][$type] $message")
    }

    @UiThread
    protected fun attachEngineWebView() {
        logViewEvent("Attaching EngineWebView")
        if (engineWebViewDelegate != null) {
            logViewEvent("EngineWebView already attached, skipping")
            return
        }

        engineWebViewDelegate = viewDelegate.createEngineWebViewInstance().also { engine ->
            engine.setAlpha(0.0f)
            engine.listener = this
            viewDelegate.addView(engine)
        }
    }

    @UiThread
    protected fun detachEngineWebView() {
        logViewEvent("Detaching EngineWebView")
        val delegate = engineWebViewDelegate
        if (delegate == null) {
            logViewEvent("EngineWebView already detached, skipping")
            return
        }

        engineWebViewDelegate = null
        viewCallback = null
        delegate.listener = null
        viewDelegate.removeView(delegate)
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
                currentRoute = route,
                action = action,
                name = name
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

    override fun routeLoaded(route: String) {
        logViewEvent("Route loaded: $route")
        currentRoute = route
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

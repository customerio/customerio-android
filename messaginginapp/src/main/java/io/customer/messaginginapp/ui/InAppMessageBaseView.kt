package io.customer.messaginginapp.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.UrlQuerySanitizer
import android.util.AttributeSet
import android.util.Base64
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat.startActivity
import androidx.core.net.toUri
import com.google.gson.Gson
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.engine.EngineWebConfiguration
import io.customer.messaginginapp.gist.presentation.engine.EngineWebView
import io.customer.messaginginapp.gist.presentation.engine.EngineWebViewListener
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.type.InAppMessage
import io.customer.messaginginapp.type.InlineMessageActionListener
import io.customer.sdk.core.di.SDKComponent
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * Base view class for displaying in-app messages. This class is responsible for managing common
 * in-app message functionality, such as setting up the EngineWebView, handling tap events, etc.
 * This class will be extended by specific in-app message views (e.g., ModalInAppMessageView)
 * to handle specific message types.
 */
abstract class InAppMessageBaseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
    @StyleRes defStyleRes: Int = 0
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes), EngineWebViewListener {
    internal var engineWebView: EngineWebView? = null

    protected var currentMessage: Message? = null
    protected var currentRoute: String? = null

    /**
     * Listener to handle action clicks from inline in-app messages.
     * Set this property to receive callbacks when actions are triggered.
     */
    var actionListener: InlineMessageActionListener? = null

    protected val logger = SDKComponent.logger
    internal val inAppMessagingManager = SDKComponent.inAppMessagingManager
    internal val store: InAppMessagingState
        get() = inAppMessagingManager.getCurrentState()

    // indicates if the message is visible to user or not
    internal val isEngineVisible: Boolean
        get() = engineWebView?.alpha == 1.0f

    protected fun logViewEvent(message: String) {
        logger.debug("[InApp][View] $message")
    }

    @UiThread
    protected fun attachEngineWebView() {
        if (engineWebView != null) {
            logViewEvent("EngineWebView already attached, skipping")
            return
        }

        logViewEvent("Attaching EngineWebView")
        engineWebView = EngineWebView(context).also { view ->
            view.alpha = 0.0f
            view.listener = this
            this.addView(view)
        }
    }

    @UiThread
    protected fun detachEngineWebView() {
        val view = engineWebView ?: return
        engineWebView = null

        logViewEvent("Detaching EngineWebView")
        view.listener = null
        removeView(view)
    }

    fun setup(message: Message) {
        logViewEvent("Setting up EngineWebView with message: $message")
        val engineWebConfiguration = EngineWebConfiguration(
            siteId = store.siteId,
            dataCenter = store.dataCenter,
            messageId = message.messageId,
            instanceId = message.instanceId,
            endpoint = store.environment.getEngineApiUrl(),
            properties = message.properties
        )

        currentMessage = message
        engineWebView?.setup(engineWebConfiguration)
    }

    fun stopLoading() {
        logViewEvent("Stopping EngineWebView loading")
        engineWebView?.stopLoading()
    }

    override fun tap(name: String, action: String, system: Boolean) {
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
            listener.onActionClick(inAppMessage, action, name)
        }

        when {
            action.startsWith("gist://") -> {
                val gistAction = URI(action)
                val urlQuery = UrlQuerySanitizer(action)
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
                        val intent = Intent(Intent.ACTION_VIEW)
                        intent.data = url.toUri()
                        logViewEvent("Opening URL: $url")
                        startActivity(context, intent, null)
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
                        val parameterBinary = Base64.decode(propertiesBase64, Base64.DEFAULT)
                        val parameterString = String(parameterBinary, StandardCharsets.UTF_8)
                        val map: Map<String, Any> = HashMap()
                        val properties = Gson().fromJson(parameterString, map.javaClass)
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
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = action.toUri()
                    intent.flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    startActivity(context, intent, null)

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
}

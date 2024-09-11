package io.customer.messaginginapp.gist.presentation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.UrlQuerySanitizer
import android.util.AttributeSet
import android.util.Base64
import android.util.DisplayMetrics
import android.widget.FrameLayout
import androidx.core.content.ContextCompat.startActivity
import com.google.gson.Gson
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.engine.EngineWebConfiguration
import io.customer.messaginginapp.gist.presentation.engine.EngineWebView
import io.customer.messaginginapp.gist.presentation.engine.EngineWebViewListener
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.sdk.core.di.SDKComponent
import java.net.URI
import java.nio.charset.StandardCharsets

class GistView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), EngineWebViewListener {

    private var engineWebView: EngineWebView? = EngineWebView(context)
    private var currentMessage: Message? = null
    private var currentRoute: String? = null
    private var firstLoad: Boolean = true
    var listener: GistViewListener? = null
    private val inAppMessagingManager = SDKComponent.inAppMessagingManager
    val logger = SDKComponent.logger
    private val store: InAppMessagingState
        get() = inAppMessagingManager.getCurrentState()

    // indicates if the message is visible to user or not
    internal val isEngineVisible: Boolean
        get() = engineWebView?.alpha == 1.0f

    init {
        engineWebView?.let { engineWebView ->
            engineWebView.alpha = 0.0f
            engineWebView.listener = this
        }
        this.addView(engineWebView)
    }

    fun setup(message: Message) {
        logger.debug("GistView setup: $message")
        currentMessage = message
        currentMessage?.let { message ->
            val engineWebConfiguration = EngineWebConfiguration(
                siteId = store.siteId,
                dataCenter = store.dataCenter,
                messageId = message.messageId,
                instanceId = message.instanceId,
                endpoint = store.environment.getEngineApiUrl(),
                properties = message.properties
            )
            engineWebView?.setup(engineWebConfiguration)
        }
    }

    fun stopLoading() {
        engineWebView?.stopLoading()
    }

    override fun tap(name: String, action: String, system: Boolean) {
        var shouldLogAction = true
        currentMessage?.let { message ->
            currentRoute?.let { route ->
                inAppMessagingManager.dispatch(InAppMessagingAction.EngineAction.Tap(message = message, route = route, name = name, action = action))
                when {
                    action.startsWith("gist://") -> {
                        val gistAction = URI(action)
                        val urlQuery = UrlQuerySanitizer(action)
                        when (gistAction.host) {
                            "close" -> {
                                shouldLogAction = false
                                logger.debug("Dismissing from action: $action")
                                inAppMessagingManager.dispatch(InAppMessagingAction.DismissMessage(message = message, viaCloseAction = true))
                            }

                            "loadPage" -> {
                                val url = urlQuery.getValue("url")
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.data = Uri.parse(url)
                                logger.debug("Opening URL: $url")
                                startActivity(context, intent, null)
                            }

                            "showMessage" -> {
                                inAppMessagingManager.dispatch(InAppMessagingAction.DismissMessage(message = message, shouldLog = false))
                                val messageId = urlQuery.getValue("messageId")
                                val propertiesBase64 = urlQuery.getValue("properties")
                                val parameterBinary = Base64.decode(propertiesBase64, Base64.DEFAULT)
                                val parameterString = String(parameterBinary, StandardCharsets.UTF_8)
                                val map: Map<String, Any> = HashMap()
                                val properties = Gson().fromJson(parameterString, map.javaClass)
                                logger.debug("Showing message: $messageId")
                                inAppMessagingManager.dispatch(InAppMessagingAction.LoadMessage(Message(messageId = messageId, properties = properties)))
                            }

                            else -> {
                                shouldLogAction = false
                                logger.debug("Gist action unhandled: $action")
                            }
                        }
                    }

                    system -> {
                        try {
                            shouldLogAction = false
                            logger.debug("Dismissing from system action: $action")
                            inAppMessagingManager.dispatch(InAppMessagingAction.DismissMessage(message = message, shouldLog = false))
                            val intent = Intent(Intent.ACTION_VIEW)
                            intent.data = Uri.parse(action)
                            intent.flags =
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            startActivity(context, intent, null)
                        } catch (e: ActivityNotFoundException) {
                            logger.debug("System action not handled: $action")
                        }
                    }
                }
                if (shouldLogAction) {
                    logger.debug("Action selected: $action")
                }
            }
        }
    }

    override fun routeError(route: String) {
        logger.debug("GistView Route error: $route")
        currentMessage?.let { message ->
            inAppMessagingManager.dispatch(InAppMessagingAction.EngineAction.MessageLoadingFailed(message))
        }
    }

    override fun routeLoaded(route: String) {
        logger.debug("GistView Route loaded: $route")
        currentRoute = route
        if (firstLoad) {
            firstLoad = false
            engineWebView?.let { engineWebView ->
                engineWebView.alpha = 1.0f
            }
            currentMessage?.let { message ->
                inAppMessagingManager.dispatch(InAppMessagingAction.DisplayMessage(message))
            }
        }
    }

    override fun error() {
        logger.debug("GistView Error loading engine")
        currentMessage?.let { message ->
            inAppMessagingManager.dispatch(InAppMessagingAction.EngineAction.MessageLoadingFailed(message))
        }
    }

    override fun bootstrapped() {
        logger.debug("GistView Engine bootstrapped")
        // Cleaning after engine web is bootstrapped and all assets downloaded.
        currentMessage?.let { message ->
            if (message.messageId == "") {
                engineWebView = null
                currentMessage = null
                listener = null
            }
        }
    }

    override fun routeChanged(newRoute: String) {
        logger.debug("GistView Route changed: $newRoute")
    }

    override fun sizeChanged(width: Double, height: Double) {
        logger.debug("GistView Size changed: $width x $height")
        listener?.onGistViewSizeChanged(getSizeBasedOnDPI(width.toInt()), getSizeBasedOnDPI(height.toInt()))
    }

    private fun getSizeBasedOnDPI(size: Int): Int {
        return size * context.resources.displayMetrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT
    }
}

interface GistViewListener {
    fun onGistViewSizeChanged(width: Int, height: Int) {}
}

package io.customer.messaginginapp.gist.presentation.engine

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.gson.Gson
import io.customer.sdk.core.di.SDKComponent

internal data class EngineWebMessage(
    val gist: EngineWebEvent
)

internal data class EngineWebEvent(
    val instanceId: String,
    val method: String,
    val parameters: Map<String, Any>? = null
)

class EngineWebViewInterface(private val listener: EngineWebViewListener) {
    private val logger = SDKComponent.logger

    // Indicates whether the interface is attached to a web view and should continue to process messages
    private var isAttachedToWebView: Boolean = false

    internal fun attach(webView: WebView) {
        // Indicates that the interface is attached to WebView and should start processing messages
        isAttachedToWebView = true
        // Add JavaScript interface to WebView so that it can communicate with the interface
        webView.addJavascriptInterface(this, JAVASCRIPT_INTERFACE_NAME)
    }

    internal fun detach(webView: WebView) {
        // Indicates that the interface is no longer attached to WebView and should stop processing messages
        isAttachedToWebView = false
        // Remove JavaScript interface from WebView
        webView.removeJavascriptInterface(JAVASCRIPT_INTERFACE_NAME)
    }

    @JavascriptInterface
    fun postMessage(message: String) {
        // Check if the interface is attached to WebView
        // If not, do not process the message
        if (!isAttachedToWebView) {
            return
        }

        val event = Gson().fromJson(message, EngineWebMessage::class.java)

        logger.debug("Received event from WebView: $event")

        event.gist.parameters?.let { eventParameters ->
            when (event.gist.method) {
                "bootstrapped" -> listener.bootstrapped()
                "routeLoaded" -> {
                    (eventParameters["route"] as String).let { route -> listener.routeLoaded(route) }
                }

                "routeChanged" -> {
                    (eventParameters["route"] as String).let { route -> listener.routeChanged(route) }
                }

                "routeError" -> {
                    (eventParameters["route"] as String).let { route -> listener.routeError(route) }
                }

                "sizeChanged" -> {
                    (eventParameters["width"] as Double).let { width ->
                        (eventParameters["height"] as Double).let { height ->
                            listener.sizeChanged(width = width, height = height)
                        }
                    }
                }

                "tap" -> {
                    (eventParameters["action"] as String).let { action ->
                        (eventParameters["name"] as String).let { name ->
                            (eventParameters["system"] as Boolean).let { system ->
                                listener.tap(name = name, action = action, system = system)
                            }
                        }
                    }
                }

                "error" -> listener.error()
            }
        }
    }

    internal companion object {
        // JavaScript interface name used to communicate with the web view
        internal const val JAVASCRIPT_INTERFACE_NAME = "appInterface"
    }
}

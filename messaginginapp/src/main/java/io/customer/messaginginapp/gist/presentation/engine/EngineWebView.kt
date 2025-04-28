package io.customer.messaginginapp.gist.presentation.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.http.SslError
import android.util.AttributeSet
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.google.gson.Gson
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.data.model.engine.EngineWebConfiguration
import io.customer.messaginginapp.gist.utilities.ElapsedTimer
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.sdk.core.di.SDKComponent
import java.util.Timer
import java.util.TimerTask

internal class EngineWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), EngineWebViewListener, LifecycleObserver {

    var listener: EngineWebViewListener? = null
    private var timer: Timer? = null
    private var timerTask: TimerTask? = null
    private var webView: WebView? = null
    private var elapsedTimer: ElapsedTimer = ElapsedTimer()
    private val engineWebViewInterface = EngineWebViewInterface(this)
    private val logger = SDKComponent.logger

    private val inAppMessagingManager = SDKComponent.inAppMessagingManager

    private val state: InAppMessagingState
        get() = inAppMessagingManager.getCurrentState()

    init {
        // exception handling is required for webview in-case webview is not supported in the device
        try {
            webView = WebView(context)
            this.addView(webView)
            logger.debug("EngineWebView created")
        } catch (e: Exception) {
            logger.error("Error while creating EngineWebView: ${e.message}")
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun onLifecycleResumed() {
        logger.info("EngineWebView onLifecycleResumed")
        webView?.let { engineWebViewInterface.attach(webView = it) }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun onLifecyclePaused() {
        logger.info("EngineWebView onLifecyclePaused")
        webView?.let { engineWebViewInterface.detach(webView = it) }
    }

    /**
     * Releases resources associated with EngineWebView.
     * This method should be called when EngineWebView instance is no longer needed
     * and the view is already removed from the parent.
     * It stops loading WebView, removes JavaScript interface, and clears reference to WebView.
     */
    fun releaseResources() {
        try {
            val view = webView ?: return
            logger.debug("Cleaning up EngineWebView")
            if (this.parent != null) {
                logger.debug("EngineWebView is still attached to parent, skipping cleanup")
                return
            }

            webView = null
            if (view.parent != null) {
                logger.debug("Removing WebView from parent before cleanup")
                this.removeView(view)
            }

            logger.debug("Detaching JavaScript interface from EngineWebView")
            engineWebViewInterface.detach(webView = view)

            logger.debug("Stopping EngineWebView loading")
            view.stopLoading()
            // Calling destroy() on WebView to release resources.
            // This call may log errors like following on some (or most) devices:
            // [ERROR:aw_browser_terminator.cc(165)] Renderer process ($id) crash detected (code -1).
            // This is likely a Chromium/WebView issue, but calling destroy() remains the correct way
            // to properly clean up and prevent WebView from attempting further JS calls
            // or keeping the webpage alive unnecessarily in the background.
            view.destroy()
        } catch (ex: Exception) {
            logger.error("Error while releasing resources: ${ex.message}")
        }
    }

    fun stopLoading() {
        webView?.stopLoading()
        // stop the timer and clean up
        bootstrapped()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun setup(configuration: EngineWebConfiguration) {
        setupTimeout()
        elapsedTimer.start("Engine render for message: ${configuration.messageId}")
        val messageData = mapOf("options" to configuration)
        val jsonString = Gson().toJson(messageData)
        val messageUrl =
            "${state.environment.getGistRendererUrl()}/index.html"
        logger.debug("Rendering message with URL: $messageUrl")
        webView?.let {
            it.settings.javaScriptEnabled = true
            it.settings.allowFileAccess = true
            it.settings.allowContentAccess = true
            it.settings.domStorageEnabled = true
            it.settings.textZoom = 100
            it.setBackgroundColor(Color.TRANSPARENT)

            findViewTreeLifecycleOwner()?.lifecycle?.addObserver(this) ?: run {
                logger.error("Lifecycle owner not found, attaching interface to WebView manually")
                engineWebViewInterface.attach(webView = it)
            }

            it.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    val script = """
                        // Post the JSON message to the current frame's listeners
                        // Ensures internal JavaScript communication via window.addEventListener('message') remains functional
                        window.postMessage($jsonString, '*');
                        
                        // Override window.parent.postMessage to route messages to the native Android interface
                        // This is necessary only for legacy message because WebView can only attach one native interface 
                        // and we have already added it as ${EngineWebViewInterface.JAVASCRIPT_INTERFACE_NAME}.
                        window.parent.postMessage = function(message) {
                            window.${EngineWebViewInterface.JAVASCRIPT_INTERFACE_NAME}.postMessage(JSON.stringify(message));
                        }
                    """.trim()
                    view.evaluateJavascript(script) { result ->
                        logger.debug("JavaScript execution result: $result")
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    return !url.startsWith("https://code.gist.build")
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCod: Int,
                    description: String,
                    failingUrl: String?
                ) {
                    listener?.error()
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    listener?.error()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    listener?.error()
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    listener?.error()
                }
            }

            it.loadUrl(messageUrl)
        }
    }

    private fun setupTimeout() {
        timerTask = object : TimerTask() {
            override fun run() {
                if (timer != null) {
                    logger.debug("Message global timeout, cancelling display.")
                    listener?.error()
                    stopTimer()
                }
            }
        }
        timer = Timer()
        timer?.schedule(timerTask, 5000)
    }

    override fun bootstrapped() {
        stopTimer()
        listener?.bootstrapped()
    }

    override fun tap(name: String, action: String, system: Boolean) {
        listener?.tap(name, action, system)
    }

    override fun routeChanged(newRoute: String) {
        elapsedTimer.start("Engine render for message: $newRoute")
        listener?.routeChanged(newRoute)
    }

    override fun routeError(route: String) {
        listener?.routeError(route)
    }

    override fun routeLoaded(route: String) {
        elapsedTimer.end()
        listener?.routeLoaded(route)
    }

    override fun sizeChanged(width: Double, height: Double) {
        listener?.sizeChanged(width, height)
    }

    override fun error() {
        listener?.error()
    }

    private fun stopTimer() {
        timerTask?.cancel()
        timer?.cancel()
        timer?.purge()
        timer = null
    }
}

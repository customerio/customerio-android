package io.customer.messaginginapp.gist.presentation.engine

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.util.AttributeSet
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.google.gson.Gson
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.data.model.engine.EngineWebConfiguration
import io.customer.messaginginapp.gist.utilities.ElapsedTimer
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.type.ColorScheme
import io.customer.messaginginapp.type.InAppMessageError
import io.customer.messaginginapp.type.InAppMessageErrorReason
import io.customer.messaginginapp.ui.bridge.EngineWebViewDelegate
import io.customer.sdk.core.di.SDKComponent
import java.util.Timer
import java.util.TimerTask
import kotlinx.coroutines.Job

internal class EngineWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), EngineWebViewListener, DefaultLifecycleObserver, EngineWebViewDelegate {

    override var listener: EngineWebViewListener? = null
    private var timer: Timer? = null
    private var timerTask: TimerTask? = null
    private var webView: WebView? = null
    private var elapsedTimer: ElapsedTimer = ElapsedTimer()
    private val engineWebViewInterface = EngineWebViewInterface(this).apply {
        onEngineError = { error -> reportFailure(error) }
    }
    private val logger = SDKComponent.logger
    private var lastResolvedColorScheme: String? = null
    private var colorSchemeJob: Job? = null

    private val inAppMessagingManager = SDKComponent.inAppMessagingManager

    private val state: InAppMessagingState
        get() = inAppMessagingManager.getCurrentState()
    private val viewLifecycleOwner: Lifecycle?
        get() = findViewTreeLifecycleOwner()?.lifecycle

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

    override fun getView(): EngineWebView {
        return this
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        onLifecycleResumed()
    }

    fun onLifecycleResumed() {
        logger.info("EngineWebView onLifecycleResumed")
        webView?.let { engineWebViewInterface.attach(webView = it) }
        // If timerTask exists but timer doesn't, we were paused mid-load
        // Restart the timer with a fresh timeout duration
        if (timerTask != null && timer == null) {
            logger.debug("Resuming timeout timer after lifecycle resume")
            setupTimeout()
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        onLifecyclePaused()
    }

    fun onLifecyclePaused() {
        logger.info("EngineWebView onLifecyclePaused")
        webView?.let { engineWebViewInterface.detach(webView = it) }
        pauseTimer()
    }

    /**
     * Releases resources associated with EngineWebView.
     * This method should be called when EngineWebView instance is no longer needed
     * and the view is already removed from the parent.
     * It stops loading WebView, removes JavaScript interface, and clears reference to WebView.
     */
    override fun releaseResources() {
        colorSchemeJob?.cancel()
        colorSchemeJob = null
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

    override fun stopLoading() {
        webView?.stopLoading()
        colorSchemeJob?.cancel()
        colorSchemeJob = null
        // remove lifecycle observer to stop receiving further lifecycle events
        onLifecyclePaused()
        viewLifecycleOwner?.removeObserver(this)
        // stop the timer and clean up
        bootstrapped()
    }

    override fun updateColorScheme(scheme: String) {
        lastResolvedColorScheme = scheme
        val json = Gson().toJson(mapOf("action" to "updateColorScheme", "colorScheme" to scheme))
        webView?.evaluateJavascript(
            "window.dispatchEvent(new MessageEvent('message', { data: $json }));",
            null
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val colorSchemeMode = state.colorScheme
        if (colorSchemeMode != ColorScheme.AUTO) return

        val resolved = colorSchemeMode.resolve(newConfig.uiMode)
        if (resolved != lastResolvedColorScheme) {
            updateColorScheme(resolved)
        }
    }

    private fun subscribeToColorSchemeChanges() {
        colorSchemeJob?.cancel()
        colorSchemeJob = inAppMessagingManager.subscribeToAttribute(
            selector = { it.colorScheme }
        ) { colorScheme ->
            val resolved = colorScheme.resolve(context.resources.configuration.uiMode)
            post {
                if (resolved != lastResolvedColorScheme) {
                    updateColorScheme(resolved)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun setup(configuration: EngineWebConfiguration) {
        val initialColorScheme = configuration.colorScheme
        lastResolvedColorScheme = initialColorScheme
        subscribeToColorSchemeChanges()
        setupTimeout()
        elapsedTimer.start("Engine render for message: ${configuration.messageId}")
        val messageData = mapOf("options" to configuration)
        val jsonString = Gson().toJson(messageData)
        val messageUrl =
            "${state.environment.getGistRendererUrl()}/index.html"
        logger.debug("Rendering message with URL: $messageUrl")
        webView?.let {
            it.settings.javaScriptEnabled = true
            // File and content access are disabled as defense-in-depth: the renderer only loads our
            // first-party HTTPS page (navigation is locked to that origin), so it never needs to read
            // local file:// or content:// resources. These default to false on API 30+ anyway.
            it.settings.allowFileAccess = false
            it.settings.allowContentAccess = false
            it.settings.domStorageEnabled = true
            it.settings.textZoom = 100
            it.setBackgroundColor(Color.TRANSPARENT)

            viewLifecycleOwner?.addObserver(this) ?: run {
                logger.error("Lifecycle owner not found, attaching interface to WebView manually")
                onLifecycleResumed()
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
                    // If color scheme changed during page load (e.g. system theme toggled
                    // between loadUrl and onPageFinished), send the current value so it
                    // overrides the stale one baked into the initial options JSON.
                    val currentScheme = lastResolvedColorScheme
                    if (currentScheme != null && currentScheme != initialColorScheme) {
                        updateColorScheme(currentScheme)
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
                    reportFailure(
                        InAppMessageError(
                            reason = InAppMessageErrorReason.NETWORK,
                            detail = description,
                            code = errorCod
                        )
                    )
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    // Also fired per resource: a 404 on an image is not a message-level failure.
                    if (request?.isForMainFrame != true) return

                    reportFailure(
                        InAppMessageError(
                            reason = InAppMessageErrorReason.NETWORK,
                            detail = errorResponse?.reasonPhrase,
                            code = errorResponse?.statusCode
                        )
                    )
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    // Fired for every resource, not just the page — images, fonts, iframes. Only a
                    // main-frame failure means the message cannot render; reporting a subresource
                    // would dismiss a message that was otherwise fine. The deprecated overload
                    // above is main-frame-only already, so this also keeps API 21-22 consistent.
                    if (request?.isForMainFrame != true) return

                    // description/errorCode are API 23+; below that the deprecated overload above
                    // is the one the platform calls, and it carries the same detail.
                    val hasDetail = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    reportFailure(
                        InAppMessageError(
                            reason = InAppMessageErrorReason.NETWORK,
                            detail = if (hasDetail) error?.description?.toString() else null,
                            code = if (hasDetail) error?.errorCode else null
                        )
                    )
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    // Overriding this removed the platform default's cancel(), so the handler has
                    // to be resolved here or the request stays suspended. Always cancel: the
                    // renderer is first-party HTTPS and we never continue past a certificate error.
                    handler?.cancel()

                    reportFailure(
                        InAppMessageError(
                            reason = InAppMessageErrorReason.NETWORK,
                            detail = "SSL error loading the renderer",
                            code = error?.primaryError
                        )
                    )
                }

                /**
                 * The WebView's renderer process died, so the message can never finish loading.
                 *
                 * Returning `true` is the point of overriding this: it tells the platform we have
                 * handled the loss. Without it the default behaviour kills the host app's process
                 * along with the renderer, so a message that crashes its renderer would take the
                 * whole app down. Before this the SDK had no override at all, which left a blank
                 * WebView and no failure callback.
                 */
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    val didCrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        detail?.didCrash()
                    } else {
                        null
                    }
                    // Tear the dead view down before reporting. The platform treats it as unusable
                    // once the renderer is gone, and normal teardown cannot do it: releaseResources()
                    // bails out while the view is still attached, and the modal path never calls it
                    // at all. Doing it here also makes the later stopLoading()/releaseResources()
                    // calls on the dismissal path no-ops, since webView is already null.
                    releaseCrashedWebView()
                    reportFailure(
                        InAppMessageError(
                            reason = InAppMessageErrorReason.WEB_VIEW_CRASHED,
                            detail = "WebView render process gone (didCrash: $didCrash)"
                        )
                    )
                    return true
                }
            }

            it.loadUrl(messageUrl)
        }
    }

    private fun setupTimeout() {
        timerTask = object : TimerTask() {
            override fun run() {
                if (timer != null) {
                    reportFailure(
                        InAppMessageError(
                            reason = InAppMessageErrorReason.TIMEOUT,
                            detail = "Engine did not bootstrap within ${TIMEOUT_DURATION}ms"
                        )
                    )
                    cleanupTimer()
                }
            }
        }
        timer = Timer()
        timer?.schedule(timerTask, TIMEOUT_DURATION)
    }

    /**
     * Pauses the timeout timer when the app goes to background.
     * Cancels the timer but keeps timerTask as a signal that we need to restart on resume.
     */
    private fun pauseTimer() {
        if (timer == null) return
        logger.debug("Pausing timeout timer")
        timer?.cancel()
        timer?.purge()
        timer = null
        // Note: timerTask remains non-null as signal that we need to restart on resume
    }

    companion object {
        private const val TIMEOUT_DURATION = 5000L
    }

    override fun bootstrapped() {
        cleanupTimer()
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

    /**
     * Tears down a WebView whose render process has died.
     *
     * Separate from [releaseResources] on purpose. That path is for orderly teardown: it refuses to
     * run while the view is still attached, and it drives the WebView (stopLoading, then destroy) in
     * a way the platform no longer supports once the renderer is gone. Here the view is already
     * unusable, so the only safe actions are to detach it and destroy it — and it has to happen
     * while still attached, because that is the state a renderer crash leaves us in.
     */
    private fun releaseCrashedWebView() {
        colorSchemeJob?.cancel()
        colorSchemeJob = null
        cleanupTimer()

        val view = webView ?: return
        webView = null

        // Guarded step by step: the renderer is already gone, so any of these can throw and none of
        // them should stop the rest from running.
        runCatching { engineWebViewInterface.detach(webView = view) }
            .onFailure { logger.error("Error detaching JS interface from crashed WebView: ${it.message}") }
        runCatching { if (view.parent != null) removeView(view) }
            .onFailure { logger.error("Error removing crashed WebView from parent: ${it.message}") }
        runCatching { view.destroy() }
            .onFailure { logger.error("Error destroying crashed WebView: ${it.message}") }
    }

    /**
     * Single exit for every failure in this view: classify, log, then notify the listener.
     *
     * [EngineWebViewListener.error] takes no arguments and is public API, so the classified error
     * reaches the logs but not the host yet.
     */
    private fun reportFailure(error: InAppMessageError) {
        logger.error("In-app message failed: ${error.describeForLogs()}")
        listener?.error()
    }

    /**
     * Fully cleans up the timeout timer.
     * Called when loading completes (success or timeout).
     * Nulls out both timer and timerTask to indicate we're no longer waiting for load.
     */
    private fun cleanupTimer() {
        timerTask?.cancel()
        timerTask = null
        timer?.cancel()
        timer?.purge()
        timer = null
    }
}

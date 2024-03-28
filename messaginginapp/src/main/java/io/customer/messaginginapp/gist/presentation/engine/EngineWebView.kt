package io.customer.messaginginapp.gist.presentation.engine

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import android.widget.FrameLayout
import io.customer.messaginginapp.gist.data.model.engine.EngineWebConfiguration
import io.customer.messaginginapp.gist.presentation.GIST_TAG
import io.customer.messaginginapp.gist.utilities.ElapsedTimer
import java.io.UnsupportedEncodingException
import java.util.Timer
import java.util.TimerTask

internal class EngineWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), EngineWebViewListener {

    var listener: EngineWebViewListener? = null
    private var timer: Timer? = null
    private var timerTask: TimerTask? = null
    private var webView: WebView? = null
    private var elapsedTimer: ElapsedTimer = ElapsedTimer()

    init {
        // exception handling is required for webview in-case webview is not supported in the device
        try {
            webView = WebView(context)
            this.addView(webView)
        } catch (e: Exception) {
            Log.e(GIST_TAG, "Error while creating EngineWebView: ${e.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun setup(configuration: EngineWebConfiguration) {
        setupTimeout()
    }

    private fun encodeToBase64(text: String): String? {
        val data: ByteArray?
        try {
            data = text.toByteArray(charset("UTF-8"))
        } catch (ex: UnsupportedEncodingException) {
            Log.e(GIST_TAG, "Unsupported encoding exception")
            return null
        }
        return Base64.encodeToString(data, Base64.URL_SAFE)
    }

    private fun setupTimeout() {
        timerTask = object : TimerTask() {
            override fun run() {
                if (timer != null) {
                    Log.i(GIST_TAG, "Message global timeout, cancelling display.")
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

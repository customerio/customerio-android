package io.customer.messaginginapp.gist.presentation.engine

import android.webkit.WebView

/**
 * WebViewClient interceptor for EngineWebView
 * This interface is used to intercept the WebViewClient events while testing
 */
internal interface EngineWebViewClientInterceptor {
    /**
     * This method is called when the WebViewClient receives onPageFinished event
     */
    fun onPageFinished(view: WebView, url: String?)
}

package io.customer.messaginginapp.gist.presentation.engine

import android.util.Log
import android.webkit.JavascriptInterface
import io.customer.messaginginapp.gist.presentation.GIST_TAG

internal data class EngineWebMessage(
    val gist: EngineWebEvent
)

internal data class EngineWebEvent(
    val instanceId: String,
    val method: String,
    val parameters: Map<String, Any>? = null
)

class EngineWebViewInterface constructor(
    listener: EngineWebViewListener
) {
    private var listener: EngineWebViewListener = listener

    @JavascriptInterface
    fun postMessage(message: String) {
        Log.i(GIST_TAG, message)
    }
}

package io.customer.messaginginapp.testutils

import android.webkit.WebView
import io.customer.messaginginapp.gist.data.model.Message
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * In-app message driver responsible for mocking message lifecycle behavior similar
 * to actual message rendered in WebView.
 * The driver allows to block at specific message state and unblock when needed.
 * The driver also provides deferred values to listen for specific message state changes.
 */
class GistEngineMessageDriver(val message: Message) {
    private val messageStatesDeferred: ConcurrentHashMap<MessageState, CompletableDeferred<Unit>> = ConcurrentHashMap()
    private var currentState: MessageState? = null
    private var blockAtMessageState: MessageState? = null
    private lateinit var callback: Callback
    lateinit var webView: WebView

    fun setup(callback: Callback, webView: WebView) {
        this.callback = callback
        this.webView = webView
    }

    fun messageStateDeferred(messageState: MessageState): CompletableDeferred<Unit> {
        return messageStatesDeferred.getOrPut(messageState) { CompletableDeferred() }
    }

    fun blockAt(messageState: MessageState) {
        blockAtMessageState = messageState
    }

    fun unblock() {
        blockAtMessageState = null
        moveToNextState()
    }

    fun onStateComplete(messageState: MessageState) {
        currentState = messageState
        messageStateDeferred(messageState).complete(Unit)

        if (messageState != blockAtMessageState) {
            moveToNextState()
        }
    }

    private fun moveToNextState() {
        val pageState = currentState ?: return

        when (pageState) {
            MessageState.HTML_LOADED -> callback.handleBootstrapped(driver = this)
            MessageState.BOOTSTRAPPED -> callback.handleRouteLoaded(driver = this)
            MessageState.COMPLETED -> {}
        }
    }

    // Message state enum to represent message lifecycle states same as actual message rendered in WebView
    // The sequence of message state is same as actual message rendered in WebView
    // i.e. HTML_LOADED -> BOOTSTRAPPED -> COMPLETED
    enum class MessageState {
        HTML_LOADED,
        BOOTSTRAPPED,
        COMPLETED
    }

    interface Callback {
        fun handleBootstrapped(driver: GistEngineMessageDriver)
        fun handleRouteLoaded(driver: GistEngineMessageDriver)
    }
}

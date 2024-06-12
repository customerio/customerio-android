package io.customer.messaginginapp.support

import android.net.Uri
import android.webkit.WebView
import com.google.gson.Gson
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.presentation.GIST_TAG
import io.customer.messaginginapp.gist.presentation.GistListener
import io.customer.messaginginapp.gist.presentation.engine.EngineWebEvent
import io.customer.messaginginapp.gist.presentation.engine.EngineWebMessage
import io.customer.messaginginapp.gist.presentation.engine.EngineWebViewClientInterceptor
import io.customer.messaginginapp.gist.presentation.engine.EngineWebViewInterface
import io.customer.messaginginapp.support.GistEngineMessageDriver.Callback
import io.customer.messaginginapp.support.GistEngineMessageDriver.MessageState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope

/**
 * This class is responsible for intercepting WebViewClient events and simulate
 * behavior of actual in-app messaging loaded in WebView by leveraging GistEngineMessageDriver.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GistEngineWebViewClientInterceptor(
    private val testCoroutineScope: TestScope
) : EngineWebViewClientInterceptor, Callback, GistListener {
    private val gson: Gson = Gson()
    private val messageDriversMap: ConcurrentHashMap<String, GistEngineMessageDriver> = ConcurrentHashMap()

    fun getMessageDriver(message: Message): GistEngineMessageDriver {
        return messageDriversMap.getOrPut(message.messageId) { GistEngineMessageDriver(message) }
    }

    // Used to reset the message driver for cases where the message is dismissed or cancelled
    private fun clearMessageDriver(message: Message) {
        messageDriversMap.remove(message.messageId)
    }

    fun reset() {
        messageDriversMap.clear()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        val uri = Uri.parse(url) ?: return
        val options = uri.getQueryParameter("options") ?: return

        val message = decodeOptionsString(options)
        val messageDriver = getMessageDriver(message)

        messageDriver.setup(callback = this, webView = view)
        messageDriver.onStateComplete(MessageState.HTML_LOADED)
    }

    override fun handleBootstrapped(driver: GistEngineMessageDriver) {
        val engineMessage = EngineWebMessage(
            gist = EngineWebEvent(
                instanceId = driver.message.instanceId,
                method = "bootstrapped",
                parameters = emptyMap()
            )
        )

        postMessage(
            driver = driver,
            engineMessage = engineMessage,
            messageState = MessageState.BOOTSTRAPPED
        )
    }

    override fun handleRouteLoaded(driver: GistEngineMessageDriver) {
        val route = driver.message
            .messageId
            .takeIf { id -> id.isNotBlank() }
        if (route == null) {
            postMessage(
                driver = driver,
                engineMessage = null,
                messageState = MessageState.COMPLETED
            )
            return
        }

        val engineMessage = EngineWebMessage(
            gist = EngineWebEvent(
                instanceId = driver.message.instanceId,
                method = "routeLoaded",
                parameters = buildMap {
                    put("route", route)
                }
            )
        )
        postMessage(
            driver = driver,
            engineMessage = engineMessage,
            messageState = MessageState.COMPLETED
        )
    }

    private fun postMessage(driver: GistEngineMessageDriver, engineMessage: EngineWebMessage?, messageState: MessageState) {
        // If the engine message is null, then the message state is already completed
        if (engineMessage == null) {
            driver.onStateComplete(messageState)
            return
        }

        val message = gson.toJson(engineMessage)
        testCoroutineScope.launch(Dispatchers.Main) {
            // Use the JavaScript interface to post the message to the WebView
            // similar to how the actual in-app messaging would interact with the WebView
            val script = "window.${EngineWebViewInterface.JAVASCRIPT_INTERFACE_NAME}.postMessage(JSON.stringify($message));"
            // Evaluate the script in the WebView and notify the driver when the message has been posted successfully
            driver.webView.evaluateJavascript(script) { response ->
                println("$GIST_TAG postMessage->evaluateJavascript - state: $messageState, response: $response, messageId: ${driver.message.messageId}")
                driver.onStateComplete(messageState)
            }
        }
    }

    override fun embedMessage(message: Message, elementId: String) {}

    override fun onMessageShown(message: Message) {
        // Do nothing as we don't need to clear the message driver until the message is dismissed or cancelled
    }

    override fun onMessageDismissed(message: Message) {
        clearMessageDriver(message)
    }

    override fun onMessageCancelled(message: Message) {
        clearMessageDriver(message)
    }

    override fun onError(message: Message) {
        clearMessageDriver(message)
    }

    override fun onAction(message: Message, currentRoute: String, action: String, name: String) {}
}

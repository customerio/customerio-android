package io.customer.messaginginapp.provider

import android.app.Application
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.presentation.GistListener
import io.customer.messaginginapp.gist.presentation.GistSdk
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.messaginginapp.type.InAppMessage

internal interface InAppMessagesProvider {
    fun initProvider(application: Application, siteId: String, region: String)
    fun setUserToken(userToken: String)
    fun setCurrentRoute(route: String)
    fun setListener(listener: InAppEventListener)
    fun dismissMessage()
    fun subscribeToEvents(
        onMessageShown: (deliveryId: String) -> Unit,
        onAction: (deliveryId: String, currentRoute: String, action: String, name: String) -> Unit,
        onError: (errorMessage: String) -> Unit
    )
    fun reset()
}

/**
 * Wrapper around Gist SDK
 */
internal class GistInAppMessagesProvider(private val provider: GistApi) :
    InAppMessagesProvider,
    GistListener {

    private var listener: InAppEventListener? = null
    private var gistSdk: GistSdk? = null

    init {
//        provider.addListener(this)
    }

    override fun initProvider(application: Application, siteId: String, region: String) {
        gistSdk = provider.initProvider(application, siteId, dataCenter = region)
    }

    override fun setUserToken(userToken: String) {
        gistSdk?.setUserId(userToken)
    }

    override fun setCurrentRoute(route: String) {
        gistSdk?.setCurrentRoute(route)
    }

    override fun reset() {
        gistSdk?.reset()
    }

    override fun setListener(listener: InAppEventListener) {
        this.listener = listener
    }

    override fun dismissMessage() {
        gistSdk?.dismissMessage()
    }

    override fun subscribeToEvents(
        onMessageShown: (String) -> Unit,
        onAction: (deliveryId: String, currentRoute: String, action: String, name: String) -> Unit,
        onError: (message: String) -> Unit
    ) {
//        provider.subscribeToEvents(
//            onMessageShown = { deliveryID ->
//                onMessageShown(deliveryID)
//            },
//            onAction = { deliveryID: String?, currentRoute: String, action: String, name: String ->
//                if (deliveryID != null && action != "gist://close") {
//                    onAction(deliveryID, currentRoute, action, name)
//                }
//            },
//            onError = { errorMessage ->
//                onError(errorMessage)
//            }
//        )
    }

    override fun embedMessage(message: Message, elementId: String) {}

    override fun onAction(message: Message, currentRoute: String, action: String, name: String) {
        listener?.messageActionTaken(
            InAppMessage.getFromGistMessage(message),
            actionValue = action,
            actionName = name
        )
    }

    override fun onError(message: Message) {
        listener?.errorWithMessage(InAppMessage.getFromGistMessage(message))
    }

    override fun onMessageDismissed(message: Message) {
        listener?.messageDismissed(InAppMessage.getFromGistMessage(message))
    }

    override fun onMessageCancelled(message: Message) {
        // Do nothing as we don't need to notify customer about this event
    }

    override fun onMessageShown(message: Message) {
        listener?.messageShown(InAppMessage.getFromGistMessage(message))
    }
}

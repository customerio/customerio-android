package io.customer.messaginginapp.provider

import android.app.Application
import build.gist.data.model.GistMessageProperties
import build.gist.data.model.Message
import build.gist.presentation.GistListener
import build.gist.presentation.GistSdk

/**
 * Wrapper around Gist SDK
 */
internal interface InAppMessagesProvider {
    fun initProvider(application: Application, organizationId: String)
    fun setUserToken(userToken: String)
    fun setCurrentRoute(route: String)
    fun clearUserToken()
    fun subscribeToEvents(onMessageShown: (String) -> Unit)
}

internal class GistInAppMessagesProvider : InAppMessagesProvider {

    override fun initProvider(application: Application, organizationId: String) {
        GistSdk.init(
            application = application,
            organizationId = organizationId
        )
    }

    override fun setUserToken(userToken: String) {
        GistSdk.setUserToken(userToken)
    }

    override fun setCurrentRoute(route: String) {
        GistSdk.setCurrentRoute(route)
    }

    override fun clearUserToken() {
        GistSdk.clearUserToken()
    }

    override fun subscribeToEvents(onMessageShown: (String) -> Unit) {
        GistSdk.addListener(object : GistListener {
            override fun embedMessage(message: Message, elementId: String) {
            }

            override fun onAction(message: Message, currentRoute: String, action: String) {
            }

            override fun onError(message: Message) {
            }

            override fun onMessageDismissed(message: Message) {
            }

            override fun onMessageShown(message: Message) {
                val deliveryID = GistMessageProperties.getGistProperties(message).campaignId
                deliveryID?.let { onMessageShown(it) }
            }
        })
    }
}

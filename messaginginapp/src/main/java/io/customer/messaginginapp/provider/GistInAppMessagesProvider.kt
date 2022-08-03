package io.customer.messaginginapp.provider

import android.app.Application

internal interface InAppMessagesProvider {
    fun initProvider(application: Application, organizationId: String)
    fun setUserToken(userToken: String)
    fun setCurrentRoute(route: String)
    fun clearUserToken()
    fun subscribeToEvents(
        onMessageShown: (deliveryId: String) -> Unit,
        onAction: (deliveryId: String, currentRoute: String, action: String) -> Unit,
        onError: (errorMessage: String) -> Unit
    )
}

/**
 * Wrapper around Gist SDK
 */
internal class GistInAppMessagesProvider(private val provider: GistApi) :
    InAppMessagesProvider {

    override fun initProvider(application: Application, organizationId: String) {
        provider.initProvider(application, organizationId)
    }

    override fun setUserToken(userToken: String) {
        provider.setUserToken(userToken)
    }

    override fun setCurrentRoute(route: String) {
        provider.setCurrentRoute(route)
    }

    override fun clearUserToken() {
        provider.clearUserToken()
    }

    override fun subscribeToEvents(
        onMessageShown: (String) -> Unit,
        onAction: (deliveryId: String, currentRoute: String, action: String) -> Unit,
        onError: (message: String) -> Unit
    ) {
        provider.subscribeToEvents(
            onMessageShown = { deliveryID ->
                onMessageShown(deliveryID)
            },
            onAction = { deliveryID: String?, currentRoute: String, action: String ->
                if (deliveryID != null && action != "gist://close") {
                    onAction(deliveryID, currentRoute, action)
                }
            },
            onError = { errorMessage ->
                onError(errorMessage)
            }
        )
    }
}

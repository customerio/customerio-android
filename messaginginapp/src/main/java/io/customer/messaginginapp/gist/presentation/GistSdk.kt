package io.customer.messaginginapp.gist.presentation

import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.di.inAppPreferenceStore
import io.customer.messaginginapp.di.pollingLifecycleManager
import io.customer.messaginginapp.di.sseLifecycleManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.state.ModalMessageState
import io.customer.messaginginapp.store.InAppPreferenceStore
import io.customer.messaginginapp.type.ColorScheme
import io.customer.sdk.core.di.SDKComponent

internal interface GistProvider {
    fun setCurrentRoute(route: String)
    fun setUserId(userId: String)
    fun setAnonymousId(anonymousId: String)
    fun dismissMessage()
    fun reset()
    fun fetchInAppMessages()
}

internal class GistSdk(
    siteId: String,
    dataCenter: String,
    environment: GistEnvironment = GistEnvironment.PROD,
    colorScheme: ColorScheme = ColorScheme.AUTO
) : GistProvider {
    private val inAppMessagingManager = SDKComponent.inAppMessagingManager
    private val state: InAppMessagingState
        get() = inAppMessagingManager.getCurrentState()
    private val logger = SDKComponent.logger
    private val inAppPreferenceStore: InAppPreferenceStore
        get() = SDKComponent.inAppPreferenceStore

    // Referenced so the lifecycle-scoped managers are instantiated and register their observers.
    // Polling and SSE are each scoped to the process foreground lifecycle (see their classes).
    private val pollingLifecycleManager = SDKComponent.pollingLifecycleManager
    private val sseLifecycleManager = SDKComponent.sseLifecycleManager

    init {
        inAppMessagingManager.dispatch(InAppMessagingAction.Initialize(siteId = siteId, dataCenter = dataCenter, environment = environment, colorScheme = colorScheme))
    }

    override fun reset() {
        inAppMessagingManager.dispatch(InAppMessagingAction.Reset)
        // Remove user token from preferences
        inAppPreferenceStore.clearAll()
        pollingLifecycleManager.reset()
        sseLifecycleManager.reset()
    }

    override fun fetchInAppMessages() {
        pollingLifecycleManager.fetchInAppMessages()
    }

    override fun setCurrentRoute(route: String) {
        logger.debug("Current gist route is: ${state.currentRoute}, new route is: $route")

        if (state.currentRoute == route) return

        inAppMessagingManager.dispatch(InAppMessagingAction.SetPageRoute(route))
    }

    override fun setUserId(userId: String) {
        if (state.userId == userId) {
            logger.debug("Current user id is already set to: ${state.userId}, ignoring new user id")
            return
        }
        inAppMessagingManager.dispatch(InAppMessagingAction.SetUserIdentifier(userId))
        // Note: fetch is now controlled by the event handler, not here
    }

    override fun setAnonymousId(anonymousId: String) {
        if (state.anonymousId == anonymousId) {
            logger.debug("Current anonymous id is already set to: ${state.anonymousId}, ignoring new anonymous id")
            return
        }
        logger.debug("Setting anonymous id to: $anonymousId")
        inAppMessagingManager.dispatch(InAppMessagingAction.SetAnonymousIdentifier(anonymousId))
        // Note: fetch is now controlled by the event handler, not here
    }

    override fun dismissMessage() {
        // only dismiss the message if it is currently displayed
        val currentModalMessageState = state.modalMessageState as? ModalMessageState.Displayed ?: return
        inAppMessagingManager.dispatch(InAppMessagingAction.DismissMessage(message = currentModalMessageState.message))
    }
}

interface GistListener {
    fun embedMessage(message: Message, elementId: String)
    fun onMessageShown(message: Message)
    fun onMessageDismissed(message: Message)
    fun onMessageCancelled(message: Message)
    fun onError(message: Message)
    fun onAction(message: Message, currentRoute: String, action: String, name: String)
}

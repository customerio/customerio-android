package io.customer.messaginginapp.gist.presentation

import android.content.Intent
import com.google.gson.Gson
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.sdk.core.di.SDKComponent

internal class GistModalManager : GistListener {
    internal var currentMessage: Message? = null
    private val inAppMessagingManager: InAppMessagingManager = SDKComponent.inAppMessagingManager

    // Flag to indicate if the modal is currently visible
    // This is used to prevent showing multiple modals at the same time while
    // modal is being dismissed with animation
    internal var isMessageModalVisible: Boolean = false

    init {
        GistSdk.addListener(this)
    }

    @Synchronized
    internal fun showModalMessage(message: Message, position: MessagePosition? = null): Boolean {
        currentMessage?.let { currentMessage ->
            inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Message $message not shown, $currentMessage is already showing."))
            return false
        }
        if (isMessageModalVisible) {
            inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Message $message not shown, modal is being dismissed."))
            return false
        }

        inAppMessagingManager.dispatch(InAppMessagingAction.ShowModal(message))
        isMessageModalVisible = true
        currentMessage = message

        val intent = GistModalActivity.newIntent(GistSdk.application)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.putExtra(GIST_MESSAGE_INTENT, Gson().toJson(message))
        intent.putExtra(GIST_MODAL_POSITION_INTENT, position?.toString())
        GistSdk.application.startActivity(intent)
        return true
    }

    internal fun dismissActiveMessage() {
        currentMessage?.let { message ->
            inAppMessagingManager.dispatch(InAppMessagingAction.DismissModal(message))
            GistSdk.dismissPersistentMessage(message)
        } ?: run {
            inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("No modal messages to dismiss."))
        }
    }

    override fun onMessageDismissed(message: Message) {
        inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Message $message dismissed."))
        if (message.instanceId == currentMessage?.instanceId) {
            inAppMessagingManager.dispatch(InAppMessagingAction.DismissModal(message))
            currentMessage = null
        }
    }

    override fun onMessageCancelled(message: Message) {
        inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Message $message cancelled."))
        if (message.instanceId == currentMessage?.instanceId) {
            inAppMessagingManager.dispatch(InAppMessagingAction.CancelModal(message))
            currentMessage = null
        }
    }

    override fun onError(message: Message) {
        inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Error occurred for message $message."))
        if (message.instanceId == currentMessage?.instanceId) {
            inAppMessagingManager.dispatch(InAppMessagingAction.OnShowError(message))
            currentMessage = null
        }
    }

    override fun embedMessage(message: Message, elementId: String) {}

    override fun onMessageShown(message: Message) {}

    override fun onAction(message: Message, currentRoute: String, action: String, name: String) {}

    internal fun clearCurrentMessage() {
        currentMessage = null
    }
}

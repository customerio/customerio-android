package io.customer.messaginginapp.gist.data.listeners

import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.presentation.GistListener

class Queue : GistListener {
    override fun embedMessage(message: Message, elementId: String) {
        TODO("Not yet implemented")
    }

    override fun onMessageShown(message: Message) {
        TODO("Not yet implemented")
    }

    override fun onMessageDismissed(message: Message) {
        TODO("Not yet implemented")
    }

    override fun onError(message: Message) {
        TODO("Not yet implemented")
    }

    override fun onAction(message: Message, currentRoute: String, action: String, name: String) {
        TODO("Not yet implemented")
    }
}

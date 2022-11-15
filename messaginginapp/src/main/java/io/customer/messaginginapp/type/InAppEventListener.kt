package io.customer.messaginginapp.type

interface InAppEventListener {
    fun messageShown(message: InAppMessage)
    fun messageDismissed(message: InAppMessage)
    fun errorWithMessage(message: InAppMessage)
    fun messageActionTaken(message: InAppMessage, currentRoute: String, action: String, name: String)
}

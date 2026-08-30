package io.customer.messaginginapp.type

interface InAppEventListener {
    fun messageShown(message: InAppMessage)
    fun messageDismissed(message: InAppMessage)
    fun errorWithMessage(message: InAppMessage)

    /**
     * Called when an in-app message fails to load or render, with the reason it failed.
     *
     * Prefer this over [errorWithMessage]. Branch on [InAppMessageError.reason];
     * [InAppMessageError.detail] is diagnostic text meant for logs, not for parsing.
     *
     * Defaulted so listeners written before the reason existed keep compiling and keep working.
     */
    fun errorWithMessage(message: InAppMessage, error: InAppMessageError) = errorWithMessage(message)
    fun messageActionTaken(message: InAppMessage, actionValue: String, actionName: String)
}

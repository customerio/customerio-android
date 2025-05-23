package io.customer.messaginginapp.type

/**
 * Listener interface for inline in-app message action events.
 * Implement this interface to receive callbacks when actions are triggered in an inline message.
 */
interface InlineMessageActionListener {
    /**
     * Called when a custom button is tapped in an inline message.
     *
     * @param message The in-app message that triggered the action
     * @param actionValue The value associated with the action (typically a URL or identifier)
     * @param actionName The name of the action that was triggered
     */
    fun onActionClick(message: InAppMessage, actionValue: String, actionName: String)
}

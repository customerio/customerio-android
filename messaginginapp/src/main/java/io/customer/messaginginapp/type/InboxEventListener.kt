package io.customer.messaginginapp.type

/**
 * Listener the host app registers to be notified when an action is taken on a visual notification
 * inbox message (e.g. the user taps a button/link inside a rendered message).
 *
 * Mirrors [InAppEventListener.messageActionTaken], but unlike in-app — where the callback is purely
 * observational — the inbox callback lets the host **intercept** the action: returning `true`
 * signals the host handled it and the SDK performs no default behavior; returning `false` (or not
 * registering a listener at all) lets the SDK run its default handling (open an http(s) url in the
 * system browser; deeplinks are left to the host).
 *
 * The SDK's built-in dismiss action is handled by the SDK regardless of this listener.
 */
interface InboxEventListener {
    /**
     * Called when a non-dismiss action is taken on an inbox message.
     *
     * @param message identity of the inbox message the action was taken on.
     * @param actionName the Jist action name (e.g. `messageAction`).
     * @param actionValue the resolved action value, typically the action's url (may be empty if the
     * action carried no url).
     * @return `true` if the host fully handled the action (the SDK does nothing further); `false`
     * to let the SDK apply its default handling.
     */
    fun messageActionTaken(message: InboxActionMessage, actionName: String, actionValue: String): Boolean

    /**
     * Observational callback fired when a message is first shown/rendered in the inbox view. Fired
     * once per message (deduped) while the view is displayed. Purely informational — it does not
     * affect SDK behavior. Default no-op so the interface stays source-compatible.
     *
     * @param message identity of the inbox message that was shown.
     */
    fun messageShown(message: InboxActionMessage) {}

    /**
     * Observational callback fired when a message is marked opened (the inbox panel opening
     * auto-marks the currently-shown unopened messages). Purely informational. Default no-op so the
     * interface stays source-compatible.
     *
     * @param message identity of the inbox message that was opened.
     */
    fun messageOpened(message: InboxActionMessage) {}

    /**
     * Observational callback fired when a message is dismissed/removed from the inbox. Purely
     * informational. Default no-op so the interface stays source-compatible.
     *
     * @param message identity of the inbox message that was dismissed.
     */
    fun messageDismissed(message: InboxActionMessage) {}
}

/**
 * Lightweight, public identity of an inbox message handed to [InboxEventListener]. Exposes the
 * stable identifiers a host needs to correlate the action with its own data, without leaking the
 * SDK's internal message/render types.
 *
 * @param messageId the inbox message's queue id.
 * @param deliveryId the message's delivery id, when present.
 */
data class InboxActionMessage(
    val messageId: String,
    val deliveryId: String?
)

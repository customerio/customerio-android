package io.customer.messagingpush.data.communication

import android.app.Notification
import android.content.Context
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload

/**
 * Lets the host app render live notifications itself, instead of using the SDK's
 * built-in templates.
 *
 * Registered via
 * [io.customer.messagingpush.MessagingPushModuleConfig.Builder.setLiveNotificationCallback].
 * This is deliberately separate from [CustomerIOPushNotificationCallback] so that
 * implementing live-notification rendering never forces a change on apps that only
 * customise standard push, and vice versa.
 */
interface CustomerIOLiveNotificationsCallback {
    /**
     * Called for every live notification the SDK is about to post. Return a
     * fully-built [Notification] to take complete control of its appearance and
     * intents, or `null` to fall back to the SDK's built-in template.
     *
     * Required for customer-defined activity types (enabled via
     * [io.customer.messagingpush.MessagingPushModuleConfig.Builder.enableCustomLiveNotificationTypes]),
     * which have no built-in template; if this returns `null` for such a type, the
     * notification is dropped.
     *
     * The SDK still owns the posting lifecycle: it posts the returned notification
     * keyed by the activity id (so later updates replace it) and cancels it on
     * `end`. It does NOT modify the returned notification, so any dismissal
     * reporting is the app's responsibility.
     *
     * Called on a background thread. Throwing from here is caught and logged by
     * the SDK, but the notification is then dropped — prefer returning `null`.
     *
     * @param payload parsed live-notification payload (activity id + flattened
     * fields in [CustomerIOParsedPushPayload.extras]).
     * @param context reference to application context.
     */
    fun createLiveNotification(
        payload: CustomerIOParsedPushPayload,
        context: Context
    ): Notification?
}

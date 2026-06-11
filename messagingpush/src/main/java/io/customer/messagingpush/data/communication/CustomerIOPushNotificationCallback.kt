package io.customer.messagingpush.data.communication

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload

interface CustomerIOPushNotificationCallback {
    /**
     * Callback to notify host app when deeplink click action is performed.
     *
     * @param context reference to application context
     * @param payload data received for the notification
     * @return [Unit] non null, depicting that customer is going to launch activities on notification click;
     * and null to let the SDK handle this
     *
     * NOTE: If your app is targeting Android 12 or greater, be careful of
     * launching intents outside the app as it can affect the notification
     * open metrics tracking
     */
    fun onNotificationClicked(
        payload: CustomerIOParsedPushPayload,
        context: Context
    ): Unit? {
        return null
    }

    /**
     * Called when all attributes for the notification has been set by the SDK
     * and the notification is about to be pushed to system tray.
     * <p/>
     * Please note that overriding the pending intent for notification is not
     * allowed as it can affect tracking and other metrics. Please override
     * [onNotificationClicked] instead to launch desired intent(s).
     * <p/>
     * @see [onNotificationClicked] to override click action
     *
     * @param payload data received for the notification
     * @param builder notification builder that is being used to build
     * notification attributes
     */
    fun onNotificationComposed(
        payload: CustomerIOParsedPushPayload,
        builder: NotificationCompat.Builder
    ) = Unit

    /**
     * Called for live notifications to let the host app render the notification
     * itself. Return a fully-built [Notification] to take complete control of
     * its appearance and intents, or null to use the SDK's built-in template.
     *
     * Required for customer-defined activity types (registered via
     * [io.customer.messagingpush.MessagingPushModuleConfig.Builder.registerLiveNotificationTypes]),
     * which have no built-in template; if this returns null for such a type, the
     * notification is dropped.
     *
     * The SDK still owns the posting lifecycle: it posts the returned
     * notification keyed by `activity_id` (so later updates replace it) and
     * cancels it on `end`. It does NOT modify the returned notification, so any
     * dismissal reporting is the app's responsibility.
     *
     * @param payload parsed live-notification payload (activity id + flattened
     * fields in [CustomerIOParsedPushPayload.extras]).
     * @param context reference to application context.
     */
    fun createLiveNotification(
        payload: CustomerIOParsedPushPayload,
        context: Context
    ): Notification? = null
}

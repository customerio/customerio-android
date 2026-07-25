package io.customer.messagingpush.data.communication

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
}

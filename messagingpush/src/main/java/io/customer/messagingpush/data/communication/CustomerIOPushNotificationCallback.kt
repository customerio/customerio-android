package io.customer.messagingpush.data.communication

import android.content.Context
import androidx.core.app.TaskStackBuilder
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload

interface CustomerIOPushNotificationCallback {
    /**
     * Callback to notify host app when deeplink click action is performed.
     *
     * @param context reference to application context
     * @param payload data received for the notification
     * @return [TaskStackBuilder] to launch activities on notification click;
     * null to let the SDK handle this
     *
     * NOTE: If your app is targeting Android 12 or greater, be careful of
     * launching intents outside the app as it can affect the notification
     * open metrics tracking
     */
    fun createTaskStackFromPayload(
        context: Context,
        payload: CustomerIOParsedPushPayload
    ): TaskStackBuilder?
}

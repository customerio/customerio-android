package io.customer.messagingpush.data.communication

import android.content.Intent
import androidx.core.app.TaskStackBuilder
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload

interface CustomerIOPushNotificationCallback {
    /**
     * Callback called when deeplink action is performed.
     *
     * @param payload payload data received for the notification
     * @return intent to launch on notification click; null to let the SDK handle this
     */
    fun createContentIntentFromPayload(payload: CustomerIOParsedPushPayload): Intent?

    /**
     * Callback called when deeplink action is performed, this can be used to
     * open activities stack for the link.
     *
     * @param payload payload data received for the notification
     * @return list of intents to add to [TaskStackBuilder]; null to let the SDK
     * handle this, and empty to do nothing
     */
    fun createContentIntentsFromPayload(
        payload: CustomerIOParsedPushPayload
    ): List<Intent>? = listOfNotNull(
        createContentIntentFromPayload(payload)
    ).takeIf { list -> list.isNotEmpty() }
}

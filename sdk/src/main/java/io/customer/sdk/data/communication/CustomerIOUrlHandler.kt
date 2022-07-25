package io.customer.sdk.data.communication

import android.content.Intent
import androidx.core.app.TaskStackBuilder

interface CustomerIOUrlHandler {

    /**
     * Callback called when deeplink action is performed.
     *
     * @param url link received in notification data
     * @return intent to launch on notification click; null to let the SDK handle this
     */
    fun createIntentForLink(url: String?): Intent?

    /**
     * Callback called when deeplink action is performed, this can be used to
     * open activities stack for the link.
     *
     * @param url link received in notification data
     * @return list of intents to add to [TaskStackBuilder]; null to let the SDK
     * handle this, and empty to do nothing
     */
    fun createIntentsForLink(url: String?): List<Intent>? = listOfNotNull(
        createIntentForLink(url)
    )
}

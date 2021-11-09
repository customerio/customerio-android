package io.customer.sdk.data.communication

import android.net.Uri

interface CustomerIOUrlHandler {

    /**
     * Callback called when deeplink action is performed.
     * @param uri Deeplink URL
     * @return Boolean return TRUE, if the URI was handled otherwise false.
     */
    fun handleIterableURL(uri: Uri): Boolean
}

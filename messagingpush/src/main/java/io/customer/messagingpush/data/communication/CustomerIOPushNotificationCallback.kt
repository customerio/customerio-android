package io.customer.messagingpush.data.communication

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
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
    ): TaskStackBuilder? = null

    /**
     * Called when all attributes for the notification has been set by the SDK
     * and the notification is about to be pushed to system tray.
     * <p/>
     * Please note that overriding the pending intent for notification is not
     * allowed as it can affect tracking and other metrics. Please override
     * [createTaskStackFromPayload] instead to launch desired intent(s).
     * <p/>
     * @see [createTaskStackFromPayload] to override click action
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
     * Called before setting flags for deep link intent. This is helpful in preventing
     * recreation of activities and handling more launch modes to achieve desired behavior.
     *
     * @param intent intent about to open for the provided link.
     * @param uri deep link uri received in the notification.
     * @param startingFromService flag to indicate whether the intent is launching intent from
     * service or not. This will be true only in Android 12 and later for links outside the host
     * app due to notification trampoline restrictions.
     * @param isThirdPartyIntent flag to indicate whether the intent being launched is outside the
     * the host app or not, true for non-host app intents, false otherwise.
     * @return flags to be set for the intent; null to don't set any flags.
     */
    fun getDeepLinkIntentFlags(
        intent: Intent,
        uri: Uri,
        startingFromService: Boolean,
        isThirdPartyIntent: Boolean
    ): Int? {
        val defaultFlags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP

        return when {
            !startingFromService -> null
            !isThirdPartyIntent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            -> defaultFlags or Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER
            else -> defaultFlags
        }
    }
}

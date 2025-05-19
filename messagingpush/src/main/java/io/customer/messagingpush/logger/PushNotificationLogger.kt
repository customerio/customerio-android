package io.customer.messagingpush.logger

import com.google.firebase.messaging.RemoteMessage
import io.customer.sdk.core.util.Logger

internal class PushNotificationLogger(private val logger: Logger) {

    companion object {
        const val TAG = "Push"
    }

    fun logGooglePlayServicesAvailable() {
        logger.debug(
            tag = TAG,
            message = "Google Play Services is available for this device"
        )
    }

    fun logGooglePlayServicesUnavailable(result: Int) {
        logger.debug(
            tag = TAG,
            message = "Google Play Services is NOT available for this device with result: $result"
        )
    }

    fun logGooglePlayServicesAvailabilityCheckFailed(throwable: Throwable) {
        logger.error(
            tag = TAG,
            message = "Checking Google Play Service availability check failed with error: : ${throwable.message}",
            throwable = throwable
        )
    }

    fun obtainingTokenStarted() {
        logger.debug(
            tag = TAG,
            message = "Getting current device token from Firebase messaging on app launch"
        )
    }

    fun obtainingTokenSuccess(token: String) {
        logger.debug(
            tag = TAG,
            message = "Got current device token: $token"
        )
    }

    fun obtainingTokenFailed(throwable: Throwable?) {
        logger.error(
            tag = TAG,
            message = "Failed to get device token with error: ${throwable?.message}",
            throwable = throwable
        )
    }

    fun logShowingPushNotification(message: RemoteMessage) {
        logger.debug(
            tag = TAG,
            message = "Showing notification for message: ${toString(message)}"
        )
    }

    private fun toString(message: RemoteMessage): String {
        val notification = message.notification ?: return message.data.toString()
        return buildString {
            appendLine("Notification:")
            appendLine("  title = ${notification.title}")
            appendLine("  body = ${notification.body}")
            appendLine("  icon = ${notification.icon}")
            appendLine("  color = ${notification.color}")
            appendLine("  imageUrl = ${notification.imageUrl}")
            appendLine("Data: ${message.data}")
        }
    }
}

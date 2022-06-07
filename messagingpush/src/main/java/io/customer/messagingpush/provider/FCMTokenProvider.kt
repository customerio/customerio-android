package io.customer.messagingpush.provider

import com.google.firebase.messaging.FirebaseMessaging
import io.customer.sdk.util.Logger

/**
 * Wrapper around FCM SDK to make the code base more testable.
 */
internal interface FCMTokenProvider {
    fun getCurrentToken(onComplete: (String?) -> Unit)
}

/**
 * This class should be as small as possible as possible because it can't be tested with automated tests. QA testing, only.
 */
class FCMTokenProviderImpl(private val logger: Logger) : FCMTokenProvider {

    override fun getCurrentToken(onComplete: (String?) -> Unit) {
        logger.debug("getting current FCM device token...")

        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val existingDeviceToken = task.result
                    logger.debug("got current FCM token: $existingDeviceToken")

                    onComplete(existingDeviceToken)
                } else {
                    logger.debug("got current FCM token: null")

                    onComplete(null)
                }
            }
        } catch (exception: Throwable) {
            logger.error(exception.message ?: "error while getting FCM token")
            onComplete(null)
        }
    }
}

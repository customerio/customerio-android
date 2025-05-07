package io.customer.messagingpush.provider

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.messaging.FirebaseMessaging
import io.customer.messagingpush.di.pushLogger
import io.customer.sdk.core.di.SDKComponent

/**
 *  Responsible for token generation and validity
 */
interface DeviceTokenProvider {
    fun isValidForThisDevice(context: Context): Boolean
    fun getCurrentToken(onComplete: (String?) -> Unit)
}

/**
 * Wrapper around FCM SDK to make the code base more testable. There is no concept of checked-exceptions in Kotlin
 * so we need to handle the exception manually.
 */
class FCMTokenProviderImpl(
    private val context: Context
) : DeviceTokenProvider {

    private val logger = SDKComponent.logger
    private val pushLogger = SDKComponent.pushLogger

    override fun isValidForThisDevice(context: Context): Boolean {
        return try {
            val result = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)

            if (result == ConnectionResult.SUCCESS) {
                pushLogger.logGooglePlayServicesAvailable()
                true
            } else {
                pushLogger.logGooglePlayServicesUnavailable(result)
                false
            }
        } catch (exception: Throwable) {
            pushLogger.logGooglePlayServicesAvailabilityCheckFailed(exception)
            false
        }
    }

    override fun getCurrentToken(onComplete: (String?) -> Unit) {
        logger.debug("getting current FCM device token...")
        try {
            if (!isValidForThisDevice(context)) {
                onComplete(null)
                return
            }

            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val existingDeviceToken = task.result
                    logger.debug("got current FCM token: $existingDeviceToken")

                    onComplete(existingDeviceToken)
                } else {
                    logger.debug("got current FCM token: null")
                    logger.error(task.exception?.message ?: "error while getting FCM token")
                    onComplete(null)
                }
            }
        } catch (exception: Throwable) {
            logger.error(exception.message ?: "error while getting FCM token")
            onComplete(null)
        }
    }
}

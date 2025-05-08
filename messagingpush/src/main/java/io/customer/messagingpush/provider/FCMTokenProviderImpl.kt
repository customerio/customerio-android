package io.customer.messagingpush.provider

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.messaging.FirebaseMessaging
import io.customer.messagingpush.logger.PushNotificationLogger
import javax.inject.Provider

/**
 *  Responsible for token generation and validity
 */
interface DeviceTokenProvider {
    fun getCurrentToken(onComplete: (String?) -> Unit)
}

/**
 * Wrapper around FCM SDK to make the code base more testable. There is no concept of checked-exceptions in Kotlin
 * so we need to handle the exception manually.
 */
internal class FCMTokenProviderImpl(
    private val context: Context,
    private val googleApiAvailabilityProvider: Provider<GoogleApiAvailability>,
    private val firebaseMessagingProvider: Provider<FirebaseMessaging>,
    private val pushLogger: PushNotificationLogger
) : DeviceTokenProvider {

    private fun isValidForThisDevice(): Boolean {
        return try {
            val result = googleApiAvailabilityProvider.get().isGooglePlayServicesAvailable(context)

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
        pushLogger.obtainingTokenStarted()
        try {
            if (!isValidForThisDevice()) {
                onComplete(null)
                return
            }

            firebaseMessagingProvider.get().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val existingDeviceToken = task.result
                    pushLogger.obtainingTokenSuccess(existingDeviceToken)

                    onComplete(existingDeviceToken)
                } else {
                    pushLogger.obtainingTokenFailed(task.exception)
                    onComplete(null)
                }
            }
        } catch (exception: Throwable) {
            pushLogger.obtainingTokenFailed(exception)
            onComplete(null)
        }
    }
}

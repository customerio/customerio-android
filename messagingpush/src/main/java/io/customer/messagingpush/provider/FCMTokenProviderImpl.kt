package io.customer.messagingpush.provider

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.sdk.data.model.DeviceTokenType

/**
 *  Responsible for token generation and validity
 */
internal interface DeviceTokenProvider {
    fun getCurrentToken(onComplete: (DeviceToken?) -> Unit)
}

/** A value fetched from Firebase, with whether it's a legacy token or an installation ID. */
internal data class DeviceToken(val value: String, val type: DeviceTokenType)

/**
 * Wrapper around FCM SDK to make the code base more testable. There is no concept of checked-exceptions in Kotlin
 * so we need to handle the exception manually.
 */
internal class FCMTokenProviderImpl(
    private val context: Context,
    private val googleApiAvailabilityProvider: () -> GoogleApiAvailability,
    private val tokenSource: FirebaseTokenSource,
    private val pushLogger: PushNotificationLogger
) : DeviceTokenProvider {

    private fun isValidForThisDevice(): Boolean {
        return try {
            val result = googleApiAvailabilityProvider().isGooglePlayServicesAvailable(context)

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

    override fun getCurrentToken(onComplete: (DeviceToken?) -> Unit) {
        pushLogger.obtainingTokenStarted()
        try {
            if (!isValidForThisDevice()) {
                onComplete(null)
                return
            }

            tokenSource.fetchToken().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val existingDeviceToken = task.result
                    pushLogger.obtainingTokenSuccess(existingDeviceToken.value)

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

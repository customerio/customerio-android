package io.customer.messagingpush.provider

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.installations.FirebaseInstallations
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
    private val firebaseInstallationsProvider: Provider<FirebaseInstallations>,
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

    /**
     * FCM deprecated registration tokens in favor of registration based on the Firebase
     * Installation ID (FID). Host apps opt in through the manifest metadata flag below,
     * which also makes the legacy token APIs throw. Mirror the flag here so we fetch the
     * identifier through whichever registration mode the host app is in.
     */
    private fun isInstallationIdRegistrationEnabled(): Boolean {
        return try {
            val applicationInfo = context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA
            )
            applicationInfo.metaData?.getBoolean(METADATA_INSTALLATION_ID_ENABLED, false) ?: false
        } catch (exception: Throwable) {
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

            if (isInstallationIdRegistrationEnabled()) {
                getInstallationId(onComplete)
            } else {
                getRegistrationToken(onComplete)
            }
        } catch (exception: Throwable) {
            pushLogger.obtainingTokenFailed(exception)
            onComplete(null)
        }
    }

    /**
     * Legacy registration flow. [FirebaseMessaging.getToken] is deprecated but remains the
     * only working API while the host app has not enabled FID based registration.
     */
    @Suppress("DEPRECATION")
    private fun getRegistrationToken(onComplete: (String?) -> Unit) {
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
    }

    /**
     * FID registration flow. [FirebaseMessaging.register] makes sure the FCM backend knows
     * about this app instance, then the FID is read from Firebase Installations and used as
     * the device token.
     */
    private fun getInstallationId(onComplete: (String?) -> Unit) {
        firebaseMessagingProvider.get().register().addOnCompleteListener { registerTask ->
            if (!registerTask.isSuccessful) {
                pushLogger.obtainingTokenFailed(registerTask.exception)
                onComplete(null)
                return@addOnCompleteListener
            }

            firebaseInstallationsProvider.get().id.addOnCompleteListener { idTask ->
                if (idTask.isSuccessful) {
                    val installationId = idTask.result
                    pushLogger.obtainingTokenSuccess(installationId)

                    onComplete(installationId)
                } else {
                    pushLogger.obtainingTokenFailed(idTask.exception)
                    onComplete(null)
                }
            }
        }
    }

    internal companion object {
        // Manifest metadata flag defined by the FCM SDK. Setting it to true switches the
        // host app to FID based registration and disables getToken()/deleteToken().
        internal const val METADATA_INSTALLATION_ID_ENABLED = "firebase_messaging_installation_id_enabled"
    }
}

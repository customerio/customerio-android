package io.customer.messagingpush.provider

import com.google.firebase.messaging.FirebaseMessaging

/**
 * Wrapper around FCM SDK to make the code base more testable.
 */
internal interface FCMTokenProvider {
    fun getCurrentToken(onComplete: (String?) -> Unit)
}

class FCMTokenProviderImpl : FCMTokenProvider {

    override fun getCurrentToken(onComplete: (String?) -> Unit) {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val existingDeviceToken = task.result

                onComplete(existingDeviceToken)
            }
        }
    }
}

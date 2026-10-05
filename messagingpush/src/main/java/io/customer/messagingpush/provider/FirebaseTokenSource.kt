package io.customer.messagingpush.provider

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.sdk.core.extensions.applicationMetaData

/**
 * Fetches the value FCM addresses this app instance by: the Firebase Installation ID (FID) when the
 * app opted in to it, otherwise the legacy registration token.
 *
 * FID registration needs firebase-messaging 25.1.0+; see `registerIfSupported` for how older versions
 * stay supported. consumer-rules.pro keeps `register()` in minified apps.
 */
internal class FirebaseTokenSource(
    private val context: Context,
    private val firebaseMessaging: () -> FirebaseMessaging,
    private val firebaseInstallations: () -> FirebaseInstallations,
    private val pushLogger: PushNotificationLogger,
    private val register: (FirebaseMessaging) -> Task<*>? = ::registerIfSupported
) {
    fun fetchToken(): Task<String> {
        val messaging = firebaseMessaging()
        if (!isOptedIn()) return messaging.token

        val registration = register(messaging)
        if (registration == null) {
            // Older Firebase ignores the opt-in and keeps issuing tokens.
            pushLogger.logInstallationIdUnsupported()
            return messaging.token
        }

        pushLogger.obtainingInstallationIdStarted()
        // register() is FID mode's getToken() without the value, so read the FID only after it
        // completes; getId() alone doesn't register with FCM.
        return registration.onSuccessTask { firebaseInstallations().id }
    }

    private fun isOptedIn(): Boolean = context.applicationMetaData()?.getBoolean(
        METADATA_INSTALLATION_ID_ENABLED,
        false
    ) == true

    companion object {
        // Firebase's own opt-in flag, read the same way firebase-messaging reads it.
        private const val METADATA_INSTALLATION_ID_ENABLED = "firebase_messaging_installation_id_enabled"

        /**
         * Calls `FirebaseMessaging.register()`, or returns null on Firebase older than 25.1.0. Uses
         * reflection because the SDK compiles against older Firebase so apps aren't forced to
         * upgrade (25.x also needs minSdk 23).
         */
        private fun registerIfSupported(messaging: FirebaseMessaging): Task<*>? {
            val registerMethod = try {
                FirebaseMessaging::class.java.getMethod("register")
            } catch (_: NoSuchMethodException) {
                return null
            }
            return registerMethod.invoke(messaging) as Task<*>
        }
    }
}

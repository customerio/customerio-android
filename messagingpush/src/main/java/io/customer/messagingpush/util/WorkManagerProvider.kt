package io.customer.messagingpush.util

import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import io.customer.messagingpush.logger.PushNotificationLogger

/**
 * Provider for WorkManager instances with safe initialization.
 * Handles cases where WorkManager may not be initialized yet.
 */
internal class WorkManagerProvider(
    private val context: Context,
    private val pushLogger: PushNotificationLogger
) {

    /**
     * Gets a WorkManager instance, initializing it if necessary.
     * This method is synchronized to prevent race conditions during initialization.
     * @return WorkManager instance or null if initialization fails
     */
    @Synchronized
    fun getWorkManager(): WorkManager? {
        return try {
            // Try to get existing instance first
            WorkManager.getInstance(context)
        } catch (e: Exception) {
            pushLogger.logWorkManagerInitializationAttempt(e)
            try {
                // Attempt to obtain app WorkManager config if available, otherwise use default config
                val config = (context.applicationContext as? Configuration.Provider)?.workManagerConfiguration ?: Configuration.Builder().build()
                WorkManager.initialize(context, config)
                // Now try to get the instance again
                WorkManager.getInstance(context)
            } catch (initException: Exception) {
                pushLogger.logWorkManagerInitializationFailed(initException)
                null
            }
        }
    }
}

package io.customer.messagingpush.util

import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import io.customer.messagingpush.logger.PushNotificationLogger

/**
 * Provider for WorkManager instances with safe initialization.
 * Handles various WorkManager initialization scenarios based on hosting app configuration:
 *
 * 1. If the hosting app doesn't use WorkManager:
 *    - WorkManager should be initialized automatically through androidx.work.WorkManagerInitializer
 *    - The first WorkManager.getInstance() should return a valid instance
 *
 * 2. If hosting app does use WorkManager:
 *    a) If host app depends on auto initialization - same as scenario 1
 *    b) If host app doesn't depend on auto initialization (removed androidx.work.WorkManagerInitializer):
 *       - If host app always initializes WorkManager early before push handling - no problems
 *       - If host app conditionally initializes or doesn't initialize by the time we need it:
 *         * We attempt to initialize WorkManager ourselves
 *         * We check if host app implements Configuration.Provider to use their config
 *         * If host app uses manual WorkManager.initialize(), we can't determine their config
 *           so we initialize with default configuration
 *
 * These scenarios are handled based on documented exceptions from WorkManager's getInstance() and initialize() methods.
 */
internal class WorkManagerProvider(
    private val context: Context,
    private val pushLogger: PushNotificationLogger
) {

    /**
     * Gets a WorkManager instance with fallback initialization strategy.
     *
     * First attempts to get an existing WorkManager instance. If that fails (indicating
     * WorkManager hasn't been initialized yet), attempts to initialize it ourselves:
     * 1. Checks if the host app implements Configuration.Provider to respect their config
     * 2. Falls back to default configuration if no custom config is available
     * 3. Attempts to get the instance again after initialization
     *
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
                // Check if host app implements Configuration.Provider, otherwise use default config
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

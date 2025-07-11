package io.customer.messagingpush.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import io.customer.messagingpush.extensions.getMetaDataString

/**
 * Manages notification channel creation and configuration.
 * This class handles reading channel configuration from metadata and creating channels.
 */
internal class NotificationChannelManager(
    private val androidVersionChecker: AndroidVersionChecker = AndroidVersionChecker()
) {

    companion object {
        // Metadata keys for notification channel configuration
        @VisibleForTesting
        internal const val METADATA_NOTIFICATION_CHANNEL_ID =
            "io.customer.messaging.push.notification_channel_id"
        
        @VisibleForTesting
        internal const val METADATA_NOTIFICATION_CHANNEL_NAME =
            "io.customer.messaging.push.notification_channel_name"
        
        @VisibleForTesting
        internal const val METADATA_NOTIFICATION_CHANNEL_IMPORTANCE =
            "io.customer.messaging.push.notification_channel_importance"
    }

    /**
     * Creates a notification channel if running on Android Oreo or higher and returns the channel ID.
     * The channel configuration is read from metadata with fallbacks to default values.
     *
     * @param context The application context
     * @param applicationName The application name (to avoid loading it twice)
     * @param appMetaData The application metadata bundle
     * @param notificationManager The notification manager instance
     * @return The channel ID to use in the notification builder
     */
    fun createNotificationChannelIfNeededAndReturnChannelId(
        context: Context,
        applicationName: String,
        appMetaData: Bundle?,
        notificationManager: NotificationManager
    ): String {
        // Get channel ID from metadata or use package name as default
        val defaultChannelId = context.packageName
        val customChannelId = appMetaData?.getMetaDataString(name = METADATA_NOTIFICATION_CHANNEL_ID)
        val channelId = customChannelId ?: defaultChannelId
        
        // Since android Oreo notification channel is needed.
        if (androidVersionChecker.isOreoOrHigher()) {
            // If a custom channel ID is provided, delete the old default channel
            if (customChannelId != null && customChannelId != defaultChannelId) {
                try {
                    // Check if the default channel exists before attempting to delete
                    notificationManager.getNotificationChannel(defaultChannelId)?.let {
                        notificationManager.deleteNotificationChannel(defaultChannelId)
                    }
                } catch (e: Exception) {
                    // Ignore exceptions when trying to delete the channel
                    // This is to prevent crashes if there's an issue with channel deletion
                }
            }
            
            // Get channel name from metadata or use default
            val channelName = appMetaData?.getMetaDataString(name = METADATA_NOTIFICATION_CHANNEL_NAME)
                ?: "$applicationName Notifications"
            
            // Get importance from metadata or use default
            val importance = appMetaData?.getInt(METADATA_NOTIFICATION_CHANNEL_IMPORTANCE,
                NotificationManager.IMPORTANCE_DEFAULT) ?: NotificationManager.IMPORTANCE_DEFAULT
            
            val channel = NotificationChannel(
                channelId,
                channelName,
                importance
            )
            notificationManager.createNotificationChannel(channel)
        }
        
        return channelId
    }
}
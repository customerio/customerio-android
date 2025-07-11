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
internal class NotificationChannelCreator(
    private val androidVersionChecker: AndroidVersionChecker = AndroidVersionChecker()
) {

    companion object {
        // Metadata keys for notification channel configuration
        @VisibleForTesting
        internal const val METADATA_NOTIFICATION_CHANNEL_ID =
            "io.customer.notification_channel_id"

        @VisibleForTesting
        internal const val METADATA_NOTIFICATION_CHANNEL_NAME =
            "io.customer.notification_channel_name"

        @VisibleForTesting
        internal const val METADATA_NOTIFICATION_CHANNEL_IMPORTANCE =
            "io.customer.notification_channel_importance"
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
        val defaultChannelId = context.packageName
        val channelId = appMetaData?.getMetaDataString(name = METADATA_NOTIFICATION_CHANNEL_ID)
            ?: defaultChannelId

        // Since android Oreo notification channel is needed.
        if (androidVersionChecker.isOreoOrHigher()) {
            val existingChannel = notificationManager.getNotificationChannel(channelId)

            val channelName = appMetaData?.getMetaDataString(name = METADATA_NOTIFICATION_CHANNEL_NAME)
                ?: "$applicationName Notifications"
            val importance = appMetaData?.getInt(
                METADATA_NOTIFICATION_CHANNEL_IMPORTANCE,
                NotificationManager.IMPORTANCE_DEFAULT
            ) ?: NotificationManager.IMPORTANCE_DEFAULT

            // Only create or update the channel if it doesn't exist or the name is different
            if (existingChannel == null || existingChannel.name != channelName) {
                val channel = NotificationChannel(
                    channelId,
                    channelName,
                    importance
                )
                notificationManager.createNotificationChannel(channel)
            }
        }

        return channelId
    }
}

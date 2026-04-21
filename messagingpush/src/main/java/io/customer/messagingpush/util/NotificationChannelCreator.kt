package io.customer.messagingpush.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import io.customer.messagingpush.di.pushLogger
import io.customer.messagingpush.extensions.getMetaDataString
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.sdk.core.di.SDKComponent

/**
 * Manages notification channel creation and configuration.
 * This class handles reading channel configuration from metadata and creating channels.
 */
internal class NotificationChannelCreator(
    private val androidVersionChecker: AndroidVersionChecker = AndroidVersionChecker(),
    private val logger: PushNotificationLogger = SDKComponent.pushLogger
) {

    companion object {
        @VisibleForTesting
        internal const val METADATA_NOTIFICATION_CHANNEL_ID =
            "io.customer.notification_channel_id"

        @VisibleForTesting
        internal const val METADATA_NOTIFICATION_CHANNEL_NAME =
            "io.customer.notification_channel_name"

        @VisibleForTesting
        internal const val METADATA_NOTIFICATION_CHANNEL_IMPORTANCE =
            "io.customer.notification_channel_importance"

        @VisibleForTesting
        internal const val METADATA_LIVE_NOTIFICATION_CHANNEL_ID =
            "io.customer.live_notification_channel_id"

        @VisibleForTesting
        internal const val METADATA_LIVE_NOTIFICATION_CHANNEL_NAME =
            "io.customer.live_notification_channel_name"

        @VisibleForTesting
        internal const val METADATA_LIVE_NOTIFICATION_CHANNEL_IMPORTANCE =
            "io.customer.live_notification_channel_importance"
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

            val channelName =
                appMetaData?.getMetaDataString(name = METADATA_NOTIFICATION_CHANNEL_NAME)
                    ?: "$applicationName Notifications"
            val rawImportance = appMetaData?.getInt(
                METADATA_NOTIFICATION_CHANNEL_IMPORTANCE,
                NotificationManager.IMPORTANCE_DEFAULT
            ) ?: NotificationManager.IMPORTANCE_DEFAULT

            // Validate that the importance value is a valid NotificationManager constant
            val importance = validateImportanceLevel(rawImportance)

            // Only create or update the channel if it doesn't exist or the name is different
            if (existingChannel == null || existingChannel.name != channelName) {
                logger.logCreatingNotificationChannel(channelId, channelName, importance)
                val channel = NotificationChannel(
                    channelId,
                    channelName,
                    importance
                )
                notificationManager.createNotificationChannel(channel)
            } else {
                logger.logNotificationChannelAlreadyExists(channelId)
            }
        }

        return channelId
    }

    /**
     * Creates a notification channel for live notifications on Android Oreo+ and returns its ID.
     *
     * Defaults to `{packageName}_cio_live` / `{appName} Live Updates` / `IMPORTANCE_HIGH`.
     * Each value can be overridden via manifest `<meta-data>`:
     * - `io.customer.live_notification_channel_id`
     * - `io.customer.live_notification_channel_name`
     * - `io.customer.live_notification_channel_importance` (integer, e.g. 4 for HIGH)
     *
     * `IMPORTANCE_HIGH` is the recommended default — it ensures the first "start" event
     * surfaces as a heads-up notification. Note: channel importance cannot be changed
     * programmatically after the channel is created on a device.
     *
     * @param context The application context
     * @param applicationName The application name used to derive the default channel name
     * @param appMetaData The application metadata bundle
     * @param notificationManager The notification manager instance
     * @return The channel ID to use in the notification builder
     */
    fun createLiveNotificationChannelIfNeededAndReturnChannelId(
        context: Context,
        applicationName: String,
        appMetaData: Bundle?,
        notificationManager: NotificationManager
    ): String {
        val channelId = appMetaData?.getMetaDataString(name = METADATA_LIVE_NOTIFICATION_CHANNEL_ID)
            ?: "${context.packageName}_cio_live"

        if (androidVersionChecker.isOreoOrHigher()) {
            val existingChannel = notificationManager.getNotificationChannel(channelId)

            val channelName = appMetaData?.getMetaDataString(name = METADATA_LIVE_NOTIFICATION_CHANNEL_NAME)
                ?: "$applicationName Live Updates"
            val rawImportance = appMetaData?.getInt(
                METADATA_LIVE_NOTIFICATION_CHANNEL_IMPORTANCE,
                NotificationManager.IMPORTANCE_HIGH
            ) ?: NotificationManager.IMPORTANCE_HIGH
            val importance = validateImportanceLevel(rawImportance)

            if (existingChannel == null || existingChannel.name != channelName) {
                logger.logCreatingNotificationChannel(channelId, channelName, importance)
                val channel = NotificationChannel(channelId, channelName, importance)
                notificationManager.createNotificationChannel(channel)
            } else {
                logger.logNotificationChannelAlreadyExists(channelId)
            }
        }

        return channelId
    }

    /**
     * Validates that the provided importance level is a valid NotificationManager importance constant.
     * If the value is not valid, it returns the default importance level.
     *
     * @param importanceLevel The importance level to validate
     * @return A valid NotificationManager importance constant
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun validateImportanceLevel(importanceLevel: Int): Int {
        return when (importanceLevel) {
            NotificationManager.IMPORTANCE_NONE,
            NotificationManager.IMPORTANCE_MIN,
            NotificationManager.IMPORTANCE_LOW,
            NotificationManager.IMPORTANCE_DEFAULT,
            NotificationManager.IMPORTANCE_HIGH,
            NotificationManager.IMPORTANCE_MAX -> importanceLevel

            else -> {
                logger.logInvalidNotificationChannelImportance(importanceLevel)
                NotificationManager.IMPORTANCE_DEFAULT
            }
        }
    }
}

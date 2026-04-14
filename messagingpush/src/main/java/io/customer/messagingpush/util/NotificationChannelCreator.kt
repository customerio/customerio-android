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
     * Creates a notification channel for live notifications if running on Android Oreo or higher
     * and returns the channel ID.
     *
     * Channel configuration is driven by the push payload with sensible defaults.
     *
     * @param context The application context
     * @param applicationName The application name used to derive the default channel name
     * @param notificationManager The notification manager instance
     * @param payloadChannelId Channel ID from the push payload, or null for the default
     * @param payloadChannelName Channel name from the push payload, or null for the default
     * @param payloadImportance Importance name from the push payload (e.g. "high", "default"), or null
     * @return The channel ID to use in the notification builder
     */
    fun createLiveNotificationChannelIfNeededAndReturnChannelId(
        context: Context,
        applicationName: String,
        notificationManager: NotificationManager,
        payloadChannelId: String?,
        payloadChannelName: String?,
        payloadImportance: String?
    ): String {
        val channelId = payloadChannelId?.takeIf { it.isNotEmpty() }
            ?: "${context.packageName}_cio_live"

        if (androidVersionChecker.isOreoOrHigher()) {
            val existingChannel = notificationManager.getNotificationChannel(channelId)

            val channelName = payloadChannelName?.takeIf { it.isNotEmpty() }
                ?: "$applicationName Live Updates"
            val importance = payloadImportance?.let { parseImportanceName(it) }
                ?: NotificationManager.IMPORTANCE_DEFAULT

            if (existingChannel == null || existingChannel.name != channelName) {
                logger.logCreatingNotificationChannel(channelId, channelName, importance)
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

    /**
     * Parses a human-readable importance name from the push payload into the
     * corresponding [NotificationManager] importance constant.
     *
     * Accepted values (case-insensitive): "none", "min", "low", "default", "high", "max".
     * Falls back to [NotificationManager.IMPORTANCE_DEFAULT] for unrecognized values.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun parseImportanceName(name: String): Int {
        return when (name.lowercase()) {
            "none" -> NotificationManager.IMPORTANCE_NONE
            "min" -> NotificationManager.IMPORTANCE_MIN
            "low" -> NotificationManager.IMPORTANCE_LOW
            "default" -> NotificationManager.IMPORTANCE_DEFAULT
            "high" -> NotificationManager.IMPORTANCE_HIGH
            "max" -> NotificationManager.IMPORTANCE_MAX
            else -> {
                logger.logInvalidNotificationChannelImportance(name.hashCode())
                NotificationManager.IMPORTANCE_DEFAULT
            }
        }
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

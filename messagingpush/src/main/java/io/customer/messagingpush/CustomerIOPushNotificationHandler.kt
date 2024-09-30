package io.customer.messagingpush

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.customer.messagingpush.activity.NotificationClickReceiverActivity
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.di.pushModuleConfig
import io.customer.messagingpush.extensions.*
import io.customer.messagingpush.processor.PushMessageProcessor
import io.customer.messagingpush.util.PushTrackingUtil.Companion.DELIVERY_ID_KEY
import io.customer.messagingpush.util.PushTrackingUtil.Companion.DELIVERY_TOKEN_KEY
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.extensions.applicationMetaData
import java.net.URL
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Class to handle PushNotification.
 *
 * Make sure [CustomerIO] instance is always initialized before using this class.
 */
internal class CustomerIOPushNotificationHandler(
    private val pushMessageProcessor: PushMessageProcessor,
    private val remoteMessage: RemoteMessage
) {

    companion object {
        const val DEEP_LINK_KEY = "link"
        const val IMAGE_KEY = "image"
        const val TITLE_KEY = "title"
        const val BODY_KEY = "body"

        const val NOTIFICATION_REQUEST_CODE = "requestCode"
        private const val FCM_METADATA_DEFAULT_NOTIFICATION_ICON =
            "com.google.firebase.messaging.default_notification_icon"
        private const val FCM_METADATA_DEFAULT_NOTIFICATION_COLOR =
            "com.google.firebase.messaging.default_notification_color"
    }

    private val diGraph = SDKComponent
    private val logger = SDKComponent.logger

    private val moduleConfig: MessagingPushModuleConfig
        get() = diGraph.pushModuleConfig

    private val bundle: Bundle by lazy {
        Bundle().apply {
            remoteMessage.data.forEach { entry ->
                putString(entry.key, entry.value)
            }
        }
    }

    fun handleMessage(
        context: Context,
        handleNotificationTrigger: Boolean = true
    ): Boolean {
        logger.debug("Handling push message. Bundle: $bundle")
        // Check if message contains a data payload.
        if (bundle.isEmpty) {
            logger.debug("Push message received is empty")
            return false
        }

        // Customer.io push notifications include data regarding the push
        // message in the data part of the payload which can be used to send
        // feedback into our system.
        val deliveryId = bundle.getString(DELIVERY_ID_KEY)
        val deliveryToken = bundle.getString(DELIVERY_TOKEN_KEY)

        if (deliveryId != null && deliveryToken != null) {
            // Use processor to track metrics properly and avoid duplication
            pushMessageProcessor.processRemoteMessageDeliveredMetrics(
                deliveryId = deliveryId,
                deliveryToken = deliveryToken
            )
        } else {
            // not a CIO push notification
            return false
        }

        // Check if message contains a notification payload.
        if (handleNotificationTrigger) {
            handleNotification(context, deliveryId, deliveryToken)
        }

        return true
    }

    private fun handleNotification(
        context: Context,
        deliveryId: String,
        deliveryToken: String
    ) {
        val applicationName = context.applicationInfo.loadLabel(context.packageManager).toString()

        val requestCode = abs(System.currentTimeMillis().toInt())

        bundle.putInt(NOTIFICATION_REQUEST_CODE, requestCode)

        val appMetaData = context.applicationMetaData()

        @DrawableRes
        val smallIcon: Int =
            remoteMessage.notification?.icon?.let { iconName -> context.getDrawableByName(iconName) }
                ?: appMetaData?.getMetaDataResource(name = FCM_METADATA_DEFAULT_NOTIFICATION_ICON)
                ?: context.applicationInfo.icon

        @ColorInt
        val tintColor: Int? =
            remoteMessage.notification?.color?.toColorOrNull()
                ?: appMetaData?.getMetaDataResource(name = FCM_METADATA_DEFAULT_NOTIFICATION_COLOR)
                    ?.let { id -> context.getColorOrNull(id) }
                ?: appMetaData?.getMetaDataString(name = FCM_METADATA_DEFAULT_NOTIFICATION_COLOR)
                    ?.toColorOrNull()

        // set title and body
        val title = bundle.getString(TITLE_KEY) ?: remoteMessage.notification?.title ?: ""
        val body = bundle.getString(BODY_KEY) ?: remoteMessage.notification?.body ?: ""

        val channelId = context.packageName
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setTicker(applicationName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        tintColor?.let { color -> notificationBuilder.setColor(color) }

        try {
            // check for image in data and notification payload to cater for both simple and rich push
            // data only payload (foreground and background)
            // notification + data payload (foreground)
            val notificationImage = bundle.getString(IMAGE_KEY) ?: remoteMessage.notification?.imageUrl?.toString()
            if (notificationImage != null) {
                addImage(notificationImage, notificationBuilder, body)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val notificationManager =
            context.getSystemService(FirebaseMessagingService.NOTIFICATION_SERVICE) as NotificationManager

        val channelName = "$applicationName Notifications"

        // Since android Oreo notification channel is needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        // set pending intent
        val payload = CustomerIOParsedPushPayload(
            extras = Bundle(bundle),
            deepLink = bundle.getString(DEEP_LINK_KEY),
            cioDeliveryId = deliveryId,
            cioDeliveryToken = deliveryToken,
            title = title,
            body = body
        )

        moduleConfig.notificationCallback?.onNotificationComposed(
            payload = payload,
            builder = notificationBuilder
        )
        createIntentForNotificationClick(
            context,
            requestCode,
            payload
        ).let { pendingIntent ->
            notificationBuilder.setContentIntent(pendingIntent)
        }

        val notification = notificationBuilder.build()
        notificationManager.notify(requestCode, notification)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun createIntentForNotificationClick(
        context: Context,
        requestCode: Int,
        payload: CustomerIOParsedPushPayload
    ): PendingIntent {
        val notifyIntent = Intent(context, NotificationClickReceiverActivity::class.java)
        notifyIntent.putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, payload)
        // In Android M, you must specify the mutability of each PendingIntent
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            notifyIntent,
            flags
        )
    }

    private fun addImage(
        imageUrl: String,
        builder: NotificationCompat.Builder,
        body: String
    ) = runBlocking {
        val style = NotificationCompat.BigPictureStyle()
            .bigLargeIcon(null)
            .setSummaryText(body)
        val url = URL(imageUrl)
        withContext(Dispatchers.IO) {
            try {
                val input = url.openStream()
                BitmapFactory.decodeStream(input)
            } catch (e: Exception) {
                null
            }
        }?.let { bitmap ->
            style.bigPicture(bitmap)
            builder.setLargeIcon(bitmap)
            builder.setStyle(style)
        }
    }
}

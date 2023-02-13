package io.customer.messagingpush

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.di.deepLinkUtil
import io.customer.messagingpush.di.moduleConfig
import io.customer.messagingpush.extensions.*
import io.customer.messagingpush.util.DeepLinkUtil
import io.customer.messagingpush.util.PushTrackingUtil.Companion.DELIVERY_ID_KEY
import io.customer.messagingpush.util.PushTrackingUtil.Companion.DELIVERY_TOKEN_KEY
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOShared
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.di.CustomerIOComponent
import io.customer.sdk.di.CustomerIOStaticComponent
import io.customer.sdk.util.Logger
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

    private val diGraph: CustomerIOComponent
        get() = CustomerIO.instance().diGraph

    private val staticGraph: CustomerIOStaticComponent
        get() = CustomerIOShared.instance().diStaticGraph

    private val logger: Logger
        get() = staticGraph.logger

    private val moduleConfig: MessagingPushModuleConfig
        get() = diGraph.moduleConfig

    private val deepLinkUtil: DeepLinkUtil
        get() = diGraph.deepLinkUtil

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
            CustomerIO.instanceOrNull(context)?.trackMetric(
                deliveryID = deliveryId,
                deviceToken = deliveryToken,
                event = MetricEvent.delivered
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

        val applicationInfo = try {
            context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA
            )
        } catch (ex: Exception) {
            logger.error("Package not found ${ex.message}")
            null
        }
        val appMetaData = applicationInfo?.metaData

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

        // This is custom logic to add images to a push notification. Some image URLs have not
        // been able to display an image in a push. https://github.com/customerio/issues/issues/7293
        // Instead, we recommend customers use `notification.image` in the FCM payload to bypass
        // our logic and use the logic from FCM's SDK for image handling instead.
        try {
            // check for image
            val notificationImage = bundle.getString(IMAGE_KEY)
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
        createIntentFromLink(
            context,
            requestCode,
            payload
        )?.let { pendingIntent ->
            notificationBuilder.setContentIntent(pendingIntent)
        }

        val notification = notificationBuilder.build()
        notificationManager.notify(requestCode, notification)
    }

    private fun createIntentFromLink(
        context: Context,
        requestCode: Int,
        payload: CustomerIOParsedPushPayload
    ): PendingIntent? {
        // In Android 12, you must specify the mutability of each PendingIntent
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        if (context.applicationInfo.targetSdkVersion > Build.VERSION_CODES.R) {
            val taskStackBuilder = moduleConfig.notificationCallback?.createTaskStackFromPayload(
                context,
                payload
            ) ?: kotlin.run {
                val pushContentIntent: Intent? = deepLinkUtil.createDeepLinkHostAppIntent(
                    context,
                    payload.deepLink
                ) ?: deepLinkUtil.createDefaultHostAppIntent(context, payload.deepLink)
                pushContentIntent?.putExtras(bundle)

                return@run pushContentIntent?.let { intent ->
                    TaskStackBuilder.create(context).run {
                        addNextIntentWithParentStack(intent)
                    }
                }
            }

            return taskStackBuilder?.getPendingIntent(requestCode, flags)
        } else {
            val pushContentIntent = Intent(CustomerIOPushReceiver.ACTION)
            pushContentIntent.setClass(context, CustomerIOPushReceiver::class.java)

            pushContentIntent.putExtra(CustomerIOPushReceiver.PUSH_PAYLOAD_KEY, payload)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                pushContentIntent,
                flags
            )
        }
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

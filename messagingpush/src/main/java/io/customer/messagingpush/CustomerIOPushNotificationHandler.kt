package io.customer.messagingpush

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.di.CustomerIOComponent
import io.customer.sdk.util.DeepLinkUtil
import io.customer.sdk.util.PushTrackingUtilImpl.Companion.DELIVERY_ID_KEY
import io.customer.sdk.util.PushTrackingUtilImpl.Companion.DELIVERY_TOKEN_KEY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.math.abs

internal class CustomerIOPushNotificationHandler(private val remoteMessage: RemoteMessage) {

    companion object {
        private const val TAG = "NotificationHandler:"

        const val DEEP_LINK_KEY = "link"
        const val IMAGE_KEY = "image"
        const val TITLE_KEY = "title"
        const val BODY_KEY = "body"

        const val NOTIFICATION_REQUEST_CODE = "requestCode"
    }

    private val diGraph: CustomerIOComponent
        get() = CustomerIO.instance().diGraph

    private val sdkConfig: CustomerIOConfig
        get() = diGraph.sdkConfig

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
        // Check if message contains a data payload.
        if (bundle.isEmpty) {
            Log.d(TAG, "Message data payload: $bundle")
            return false
        }

        // Customer.io push notifications include data regarding the push
        // message in the data part of the payload which can be used to send
        // feedback into our system.
        val deliveryId = bundle.getString(DELIVERY_ID_KEY)
        val deliveryToken = bundle.getString(DELIVERY_TOKEN_KEY)

        if (deliveryId != null && deliveryToken != null) {
            CustomerIO.instance().trackMetric(
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
            handleNotification(context)
        }

        return true
    }

    private fun handleNotification(
        context: Context
    ) {
        val applicationName = context.applicationInfo.loadLabel(context.packageManager).toString()

        val requestCode = abs(System.currentTimeMillis().toInt())

        bundle.putInt(NOTIFICATION_REQUEST_CODE, requestCode)

        val icon = context.applicationInfo.icon

        // set title and body
        val title = bundle.getString(TITLE_KEY) ?: remoteMessage.notification?.title ?: ""
        val body = bundle.getString(BODY_KEY) ?: remoteMessage.notification?.body ?: ""

        val channelId = context.packageName
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setTicker(applicationName)

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
        createIntentFromLink(
            context,
            requestCode,
            bundle.getString(DEEP_LINK_KEY)
        )?.let { pendingIntent ->
            notificationBuilder.setContentIntent(pendingIntent)
        }

        val notification = notificationBuilder.build()
        notificationManager.notify(requestCode, notification)
    }

    private fun createIntentFromLink(
        context: Context,
        requestCode: Int,
        deepLink: String?
    ): PendingIntent? {
        // In Android 12, you must specify the mutability of each PendingIntent
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pushContentIntents = sdkConfig.urlHandler?.handleCustomerIOLink(deepLink)
            ?: deepLinkUtil.createIntentsForLink(context, deepLink)
        pushContentIntents.forEach { it.putExtras(bundle) }

        if (pushContentIntents.isNotEmpty()) {
            val notificationClickedIntent: PendingIntent? = TaskStackBuilder.create(context).run {
                pushContentIntents.forEach { addNextIntentWithParentStack(it) }
                getPendingIntent(requestCode, flags)
            }
            return notificationClickedIntent
        }
        return null
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

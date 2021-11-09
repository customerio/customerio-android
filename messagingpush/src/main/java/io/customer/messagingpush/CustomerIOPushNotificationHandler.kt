package io.customer.messagingpush

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import io.customer.messagingpush.data.model.PushData
import io.customer.sdk.CustomerIO
import io.customer.sdk.data.request.MetricEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL
import kotlin.math.abs


class CustomerIOPushNotificationHandler(private val remoteMessage: RemoteMessage) {

    companion object {
        private const val TAG = "NotificationHandler:"

        private const val PUSH_NOTIFICATION_KEY = "CIO"
        const val DELIVERY_ID = "CIO-Delivery-ID"
        const val DELIVERY_TOKEN = "CIO-Delivery-Token"

        const val DEEP_LINK_KEY = "CIO-Deeplink-Key"

        const val NOTIFICATION_REQUEST_CODE = "requestCode"

        private const val CHANNEL_NAME = "CustomerIO Channel"
    }

    private val bundle: Bundle by lazy {
        Bundle().apply {
            remoteMessage.data.forEach { entry ->
                putString(entry.key, entry.value)
            }
        }
    }

    private val title by lazy {
        remoteMessage.notification?.title
    }

    private val body by lazy {
        remoteMessage.notification?.body
    }

    fun isCIOPushNotification(): Boolean {
        return bundle.getString(PUSH_NOTIFICATION_KEY) != null
    }

    fun handleMessage(
        context: Context,
        handleNotificationTrigger: Boolean = true
    ): Boolean {
        // Check if message contains a data payload.
        // You can have data only notifications.
        if (bundle.isEmpty) {
            Log.d(TAG, "Message data payload: $bundle")
            return false
        }

        // Customer.io push notifications include data regarding the push
        // message in the data part of the payload which can be used to send
        // feedback into our system.
        val deliveryId = bundle.getString(DELIVERY_ID)
        val deliveryToken = bundle.getString(DELIVERY_TOKEN)

        if (deliveryId != null && deliveryToken != null) {
            try {
                CustomerIO.instance().trackMetric(
                    deliveryID = deliveryId,
                    deviceToken = deliveryToken,
                    event = MetricEvent.delivered
                ).enqueue()
            } catch (exception: IllegalStateException) {
                Log.e(TAG, "Error while handling message: ${exception.message}")
            }
        }

        // Check if message contains a notification payload.
        if (handleNotificationTrigger) {
            handleNotification(context)
        }

        return true
    }


    @SuppressLint("LaunchActivityFromNotification")
    private fun handleNotification(
        context: Context
    ) {

        val applicationName = context.applicationInfo.loadLabel(context.packageManager).toString()

        val requestCode = abs(System.currentTimeMillis().toInt())

        bundle.putInt(NOTIFICATION_REQUEST_CODE, requestCode)

        // In Android 12, you must specify the mutability of each PendingIntent
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val icon = context.applicationInfo.icon

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
            val moshi = Moshi.Builder().build()
            val jsonAdapter: JsonAdapter<PushData> = moshi.adapter(
                PushData::class.java
            )
            val customerIOData: String? = bundle.getString(PUSH_NOTIFICATION_KEY)
            customerIOData?.let {
                val pushData = jsonAdapter.fromJson(customerIOData)
                val notificationImage = pushData?.image
                if (notificationImage != null) {
                    addImage(notificationImage, notificationBuilder)
                }
                val notificationDeepLink = pushData?.link
                if (notificationDeepLink != null) {
                    bundle.putString(DEEP_LINK_KEY, notificationDeepLink)
                }
            }
        } catch (e: Exception) {

        }

        val notificationManager =
            context.getSystemService(FirebaseMessagingService.NOTIFICATION_SERVICE) as NotificationManager

        // Since android Oreo notification channel is needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        // set pending intent
        val pushContentIntent = Intent(CustomerIOPushActionReceiver.ACTION)
        pushContentIntent.setClass(context, CustomerIOPushActionReceiver::class.java)

        pushContentIntent.putExtras(bundle)
        val notificationClickedIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            pushContentIntent,
            flags
        )
        notificationBuilder.setContentIntent(notificationClickedIntent)

        val notification = notificationBuilder.build()
        notificationManager.notify(requestCode, notification)
    }


    private fun addImage(
        imageUrl: String,
        builder: NotificationCompat.Builder
    ) = runBlocking {
        val style = NotificationCompat.BigPictureStyle()
            .bigLargeIcon(null)
            .setSummaryText(body)
        val url = URL(imageUrl)
        withContext(Dispatchers.IO) {
            try {
                val input = url.openStream()
                BitmapFactory.decodeStream(input)
            } catch (e: IOException) {
                null
            }
        }?.let { bitmap ->
            style.bigPicture(bitmap)
            builder.setLargeIcon(bitmap)
        }
    }


}

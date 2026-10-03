package io.customer.messagingpush

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.RemoteMessage
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.random
import io.customer.messagingpush.activity.NotificationClickReceiverActivity
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.extensions.parcelable
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.messagingpush.util.NotificationChannelCreator
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class CustomerIOPushNotificationHandlerTest : IntegrationTest() {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var pushNotificationHandler: CustomerIOPushNotificationHandler
    private lateinit var pushNotificationPayload: CustomerIOParsedPushPayload
    private val mockPushLogger = mockk<PushNotificationLogger>(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<PushNotificationLogger>(mockPushLogger)
                    }
                }
            }
        )

        val extras = Bundle()
        extras.putString("CIO-Delivery-ID", "anyId")
        extras.putString("CIO-Delivery-Token", "anyToken")
        pushNotificationHandler = CustomerIOPushNotificationHandler(mockk(relaxed = true), RemoteMessage(extras), NotificationChannelCreator())
        pushNotificationPayload = CustomerIOParsedPushPayload(
            extras = extras,
            deepLink = String.random,
            cioDeliveryId = String.random,
            cioDeliveryToken = String.random,
            title = String.random,
            body = String.random
        )
    }

    @Test
    fun createIntentForNotificationClick_givenAnyPayload_shouldStartNotificationClickReceiverActivity() {
        val actualPendingIntent = pushNotificationHandler.createIntentForNotificationClick(
            contextMock,
            Int.random(1000, 9999),
            pushNotificationPayload
        )

        actualPendingIntent.send()
        val nextStartedActivity = Shadows.shadowOf(applicationMock).nextStartedActivity
        val nextStartedActivityIntent = Shadows.shadowOf(nextStartedActivity)
        val nextStartedActivityPayload: CustomerIOParsedPushPayload? =
            nextStartedActivity.extras?.parcelable(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA)

        nextStartedActivityIntent.intentClass shouldBeEqualTo NotificationClickReceiverActivity::class.java
        nextStartedActivityPayload shouldBeEqualTo pushNotificationPayload
    }

    @Test
    fun handleMessage_shouldLogShowingPushNotification() {
        pushNotificationHandler.handleMessage(contextMock, true)

        assertCalledOnce { mockPushLogger.logShowingPushNotification(any()) }
    }

    @Test
    fun handleMessage_givenNonCioBundle_shouldLogPushMessageEmpty() {
        val bundle = Bundle()
        bundle.putString("anyKey", "anyValue")
        val remoteMessage = RemoteMessage(bundle)
        val handler = CustomerIOPushNotificationHandler(mockk(relaxed = true), remoteMessage, NotificationChannelCreator())

        handler.handleMessage(contextMock, false)

        assertCalledOnce { mockPushLogger.logReceivedPushMessage(remoteMessage, false) }
        assertCalledOnce { mockPushLogger.logReceivedNonCioPushMessage() }
    }

    @Test
    fun handleMessage_givenEmptyBundle_shouldLogPushMessageEmpty() {
        val bundle = Bundle()
        val remoteMessage = RemoteMessage(bundle)
        val handler = CustomerIOPushNotificationHandler(mockk(relaxed = true), remoteMessage, NotificationChannelCreator())

        handler.handleMessage(contextMock, true)

        assertCalledOnce { mockPushLogger.logReceivedPushMessage(remoteMessage, true) }
        assertCalledOnce { mockPushLogger.logReceivedEmptyPushMessage() }
    }

    @Test
    fun handleMessage_givenValidCioBundle_shouldLogPushMessageEmpty() {
        val bundle = Bundle()
        bundle.putString("CIO-Delivery-ID", "anyId")
        bundle.putString("CIO-Delivery-Token", "anyToken")
        val remoteMessage = RemoteMessage(bundle)
        val handler = CustomerIOPushNotificationHandler(mockk(relaxed = true), remoteMessage, NotificationChannelCreator())

        handler.handleMessage(contextMock, false)

        assertCalledOnce { mockPushLogger.logReceivedPushMessage(remoteMessage, false) }
        assertCalledOnce { mockPushLogger.logReceivedCioPushMessage() }
    }

    @Test
    fun handleMessage_givenHandleNotificationTriggerTrue_shouldLogShowingPushNotification() {
        pushNotificationHandler.handleMessage(contextMock, true)

        assertCalledOnce { mockPushLogger.logShowingPushNotification(any()) }
    }

    @Test
    fun handleMessage_givenHandleNotificationTriggerFalse_shouldNotLogShowingPushNotification() {
        pushNotificationHandler.handleMessage(contextMock, false)

        assertCalledNever { mockPushLogger.logShowingPushNotification(any()) }
    }

    @Test
    fun handleMessage_whenRichPushImageIsWide_thenPostsSquareLargeIconAndKeepsBigPicture() {
        val source = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLUE)
        val encoded = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.PNG, 100, encoded)
        source.recycle()
        val imageFile = temporaryFolder.newFile("wide-picture.png").apply { writeBytes(encoded.toByteArray()) }
        val message = RemoteMessage.Builder("destination").setData(
            mapOf(
                "CIO-Delivery-ID" to "delivery-id",
                "CIO-Delivery-Token" to "delivery-token",
                "title" to "Title",
                "body" to "Body",
                "image" to imageFile.toURI().toURL().toString()
            )
        ).build()
        val handler = CustomerIOPushNotificationHandler(mockk(relaxed = true), message, NotificationChannelCreator())

        handler.handleMessage(contextMock, true)

        val manager = contextMock.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = Shadows.shadowOf(manager).allNotifications.single()
        val largeIcon = notification.getLargeIcon()
        largeIcon.shouldNotBeNull()
        val icon = (largeIcon.loadDrawable(contextMock) as BitmapDrawable).bitmap
        val targetSize = (64 * contextMock.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val picture = notification.extras.parcelable<Bitmap>(Notification.EXTRA_PICTURE)
        picture.shouldNotBeNull()
        // The platform may reduce a 64 dp icon further. Compare with a full square through the same builder.
        val square = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val reference = NotificationCompat.Builder(contextMock, notification.channelId)
            .setLargeIcon(square)
            .setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture))
            .build()
        val referenceIcon = (reference.getLargeIcon().loadDrawable(contextMock) as BitmapDrawable).bitmap
        assertEquals(icon.width, icon.height)
        assertEquals(referenceIcon.width, icon.width)
        assertTrue(icon.width in 1..targetSize)
        assertEquals(Color.BLUE, icon.getPixel(icon.width / 2, icon.height / 2))
        assertEquals(picture.width, picture.height * 2)
        assertFalse(picture.isRecycled)
    }
}

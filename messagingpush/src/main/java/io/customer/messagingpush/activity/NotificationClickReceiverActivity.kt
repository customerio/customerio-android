package io.customer.messagingpush.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.customer.messagingpush.di.pushMessageProcessor
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOShared
import io.customer.sdk.tracking.TrackableScreen
import io.customer.sdk.util.Logger

/**
 * Activity to handle notification click events.
 *
 * This activity is launched when a notification is clicked. It tracks opened
 * metrics, handles the deep link and opens the desired activity in the host app.
 */
class NotificationClickReceiverActivity : Activity(), TrackableScreen {
    val logger: Logger by lazy { CustomerIOShared.instance().diStaticGraph.logger }

    override fun getScreenName(): String? {
        // Return null to prevent this screen from being tracked
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(data = intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(data = intent)
    }

    private fun handleIntent(data: Intent?) {
        if (data == null) {
            // This should never happen ideally
            logger.error("Intent is null, cannot process notification click")
        } else {
            val sdkInstance = CustomerIO.instanceOrNull(context = this)
            if (sdkInstance == null) {
                logger.error("SDK is not initialized, cannot handle notification intent")
            } else {
                sdkInstance.diGraph.pushMessageProcessor.processNotificationClick(
                    activity = this,
                    intent = data
                )
            }
        }
        finish()
    }

    companion object {
        const val NOTIFICATION_PAYLOAD_EXTRA = "CIO_NotificationPayloadExtras"
    }
}

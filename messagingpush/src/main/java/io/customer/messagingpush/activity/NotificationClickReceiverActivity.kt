package io.customer.messagingpush.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.customer.messagingpush.di.pushMessageProcessor
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import io.customer.sdk.tracking.TrackableScreen

/**
 * Activity to handle notification click events.
 *
 * This activity is launched when a notification is clicked. It tracks opened
 * metrics, handles the deep link and opens the desired activity in the host app.
 */
class NotificationClickReceiverActivity : Activity(), TrackableScreen {
    private val diGraph = SDKComponent
    val logger = SDKComponent.logger

    override fun getScreenName(): String? {
        // Return null to prevent this screen from being tracked
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SDKComponent.setupAndroidComponent(context = this)
        handleIntent(data = intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(data = intent)
    }

    private fun handleIntent(data: Intent?) {
        if (data == null || data.extras == null) {
            // This should never happen ideally
            logger.error("Intent is null, cannot process notification click")
        } else {
            diGraph.pushMessageProcessor.processNotificationClick(
                activityContext = this,
                intent = data
            )
        }
        finish()
    }

    companion object {
        const val NOTIFICATION_PAYLOAD_EXTRA = "CIO_NotificationPayloadExtras"
    }
}

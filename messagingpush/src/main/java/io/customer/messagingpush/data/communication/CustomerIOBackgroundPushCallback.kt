package io.customer.messagingpush.data.communication

import android.content.Context
import io.customer.messagingpush.data.model.CustomerIOBackgroundPushPayload

/**
 * Receives the data from a Customer.io silent push. The SDK never shows a silent push: it
 * posts no notification, creates no notification channel, opens no link and tracks no opened
 * metric. When automatic push metrics are on, it tracks the delivered metric.
 *
 * Register this callback in `Application.onCreate`, where you initialize the SDK. Firebase can
 * start your app's process in the background just to deliver a silent push, and only a callback
 * registered during startup receives it.
 *
 * The SDK calls this on the thread that handed it the message, which Firebase gives only a
 * short time. Keep the callback short and hand longer work to WorkManager.
 *
 * Silent pushes can repeat or arrive late, since Android can hold normal-priority messages
 * while the device is idle. Key your work on [CustomerIOBackgroundPushPayload.deliveryId] so a
 * repeat is harmless. The SDK calls this callback for every copy it receives.
 *
 * After a device has been offline, only the newest silent push may arrive. Treat each one as a
 * prompt to fetch the latest state.
 *
 * Silent push needs Customer.io Android SDK 5.1.0 or later. Devices on older versions, and
 * devices whose app registers no callback, report that they can't receive silent pushes, and
 * Customer.io skips them.
 */
fun interface CustomerIOBackgroundPushCallback {
    fun onBackgroundPushReceived(context: Context, payload: CustomerIOBackgroundPushPayload)
}

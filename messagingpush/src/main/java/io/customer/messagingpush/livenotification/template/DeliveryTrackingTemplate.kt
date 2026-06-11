package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `deliverytracking` template — segmented progress bar over delivery stages.
 *
 * Fields: orderId (req), recipientName (opt), statusMessage (req),
 * statusImageKey (opt), stepCurrent/stepTotal (req), estimatedArrival (opt,
 * epoch ms), driverName (opt).
 */
internal object DeliveryTrackingTemplate : LiveNotificationTemplate {

    override val name: String = TemplateRegistry.DELIVERY_TRACKING

    override fun render(
        context: Context,
        data: JSONObject,
        branding: LiveNotificationBranding?,
        smallIcon: Int,
        fallbackTintColor: Int?
    ): TemplateRenderResult {
        val orderId = data.optString("orderId")
        val recipientName = data.optStringNonEmpty("recipientName")
        val statusMessage = data.optString("statusMessage")
        val statusImageKey = data.optStringNonEmpty("statusImageKey")
        val stepCurrent = data.optInt("stepCurrent", 0)
        val stepTotal = data.optInt("stepTotal", 1).coerceAtLeast(1)
        val estimatedArrival = data.optLong("estimatedArrival").takeIf { it > 0 }
        val driverName = data.optStringNonEmpty("driverName")

        val title = recipientName?.let { "Delivery for $it" } ?: "Order #$orderId"
        val subText = when {
            driverName != null && orderId.isNotEmpty() -> "Driver: $driverName · Order #$orderId"
            driverName != null -> "Driver: $driverName"
            orderId.isNotEmpty() -> "Order #$orderId"
            else -> null
        }

        val segments = List(stepTotal) { SegmentSpec(length = 1) }

        return TemplateRenderResult(
            title = title,
            body = statusMessage,
            subText = subText,
            largeIcon = TemplateAssets.resolveBitmap(context, statusImageKey),
            accentColor = branding?.accentColor ?: fallbackTintColor,
            colorized = false,
            showProgress = true,
            progress = stepCurrent.coerceIn(0, stepTotal),
            progressMax = stepTotal,
            segments = segments,
            points = emptyList(),
            startIconRes = null,
            endIconRes = null,
            trackerIconRes = null,
            countdownUntil = estimatedArrival,
            deepLink = null
        )
    }
}

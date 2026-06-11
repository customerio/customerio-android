package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `auctionbid` template — current bid + winning/outbid state.
 *
 * Fields: itemTitle (req), itemImageKey (opt), currencySymbol (default `$`),
 * currentBid (req, preformatted string), bidCount (req, int),
 * endTime (req, epoch ms), statusMessage (req), isUserHighBidder (req, bool),
 * userBidAmount (opt, preformatted string).
 *
 * Strong visual differentiation between winning (green) and outbid (red) states
 * is conveyed via accent color + colorized notification on pre-API-36.
 */
internal object AuctionBidTemplate : LiveNotificationTemplate {

    override val name: String = TemplateRegistry.AUCTION_BID

    private const val WINNING_GREEN: Int = -0xc951c1 // #36AE3F
    private const val OUTBID_RED: Int = -0x33ccd0 // #CC3330

    override fun render(
        context: Context,
        data: JSONObject,
        branding: LiveNotificationBranding?,
        smallIcon: Int,
        fallbackTintColor: Int?
    ): TemplateRenderResult {
        val itemTitle = data.optString("itemTitle")
        val itemImageKey = data.optStringNonEmpty("itemImageKey")
        val currencySymbol = data.optStringNonEmpty("currencySymbol") ?: "$"
        val currentBid = data.optString("currentBid")
        val bidCount = data.optInt("bidCount", 0)
        val endTime = data.optLong("endTime").takeIf { it > 0 }
        val statusMessage = data.optString("statusMessage")
        val isUserHighBidder = data.optBoolean("isUserHighBidder", false)
        val userBidAmount = data.optStringNonEmpty("userBidAmount")

        val body = "$statusMessage · $currencySymbol$currentBid"
        val subText = userBidAmount
            ?.let { "Your bid: $currencySymbol$it · $bidCount bids" }
            ?: "$bidCount bids"
        val accentColor = if (isUserHighBidder) WINNING_GREEN else OUTBID_RED

        return TemplateRenderResult(
            title = itemTitle,
            body = body,
            subText = subText,
            largeIcon = TemplateAssets.resolveBitmap(context, itemImageKey),
            accentColor = accentColor,
            colorized = true,
            showProgress = false,
            progress = 0,
            progressMax = 0,
            segments = emptyList(),
            points = emptyList(),
            startIconRes = null,
            endIconRes = null,
            trackerIconRes = null,
            countdownUntil = endTime,
            deepLink = null
        )
    }
}

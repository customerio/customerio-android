package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `auction_bid` template — current bid + winning/outbid state.
 *
 * Static: itemTitle (req), itemImageKey (opt), currencySymbol (default `$`).
 * Dynamic: currentBid (req, preformatted string), bidCount (req, int),
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
        attributes: JSONObject,
        contentState: JSONObject,
        branding: LiveNotificationBranding?,
        smallIcon: Int,
        fallbackTintColor: Int?
    ): TemplateRenderResult {
        val itemTitle = attributes.optString("itemTitle")
        val itemImageKey = attributes.optString("itemImageKey").takeIf { it.isNotEmpty() }
        val currencySymbol = attributes.optString("currencySymbol").takeIf { it.isNotEmpty() } ?: "$"
        val currentBid = contentState.optString("currentBid")
        val bidCount = contentState.optInt("bidCount", 0)
        val endTime = contentState.optLong("endTime").takeIf { it > 0 }
        val statusMessage = contentState.optString("statusMessage")
        val isUserHighBidder = contentState.optBoolean("isUserHighBidder", false)
        val userBidAmount = contentState.optString("userBidAmount").takeIf { it.isNotEmpty() }

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

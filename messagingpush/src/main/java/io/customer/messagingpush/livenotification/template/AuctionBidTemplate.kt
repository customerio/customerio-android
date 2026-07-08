package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `auctionbid` template — current bid + winning/outbid state.
 *
 * Freeform slots (rendered verbatim, never composed): `header` (opt),
 * `title` (req — item), `subtitle` (opt — "47 bids"/your bid),
 * `statusMessage` (req — winning/outbid/ended).
 * Typed: `currentBid` (req, preformatted string) + `currencySymbol` (req),
 * `endTime` (req, epoch seconds), `image` (opt), `statusColor` (opt, hex),
 * `staleMessage` (opt).
 *
 * `statusColor` (sent by the server) drives the winning/outbid differentiation
 * instead of the SDK deriving green/red; when absent, branding/default is used.
 * The price (`currencySymbol` + `currentBid`) is a typed value shown as subText
 * when no freeform `subtitle` was provided.
 */
internal object AuctionBidTemplate : LiveNotificationTemplate {

    override val name: String = TemplateRegistry.AUCTION_BID

    override fun render(
        context: Context,
        data: JSONObject,
        branding: LiveNotificationBranding?,
        smallIcon: Int,
        fallbackTintColor: Int?
    ): TemplateRenderResult? {
        val title = data.optString(AuctionBidFields.TITLE)
        val header = data.optStringNonEmpty(AuctionBidFields.HEADER)
        val subtitle = data.optStringNonEmpty(AuctionBidFields.SUBTITLE)
        val statusMessage = data.optString(AuctionBidFields.STATUS_MESSAGE)
        val image = data.optStringNonEmpty(AuctionBidFields.IMAGE)
        val currencySymbol = data.optStringNonEmpty(AuctionBidFields.CURRENCY_SYMBOL) ?: "$"
        val currentBid = data.optStringNonEmpty(AuctionBidFields.CURRENT_BID)
        val endTime = data.optEpochSecondsAsMillis(AuctionBidFields.END_TIME)
        val statusColor = data.optColorInt(AuctionBidFields.STATUS_COLOR)
        val staleMessage = data.optStringNonEmpty(AuctionBidFields.STALE_MESSAGE)

        // No usable content (required `title` missing / not flattened): don't render a blank notification.
        if (title.isBlank()) {
            return null
        }

        // Freeform `subtitle` wins; otherwise show the typed price verbatim.
        val subText = subtitle
            ?: currentBid?.let { "$currencySymbol$it" }
            ?: header

        return TemplateRenderResult(
            title = title,
            // A stale push carries `staleMessage` as the current state's message; show it verbatim.
            body = staleMessage ?: statusMessage,
            subText = subText,
            largeIcon = TemplateAssets.resolveBitmap(context, image),
            accentColor = statusColor ?: branding?.accentColor ?: fallbackTintColor,
            colorized = statusColor != null,
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

package io.customer.messagingpush.livenotification.template

import android.graphics.Color
import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [AuctionBidTemplate].
 *
 * Freeform slots (`title`/`subtitle`/`statusMessage`) are rendered verbatim.
 * `statusColor` (hex, server-sent) drives the winning/outbid differentiation
 * instead of the SDK deriving green/red. The typed price (`currencySymbol` +
 * `currentBid`) fills subText only when no freeform `subtitle` was supplied.
 */
@RunWith(RobolectricTestRunner::class)
internal class AuctionBidTemplateTest : IntegrationTest() {

    private fun render(
        attributes: JSONObject = JSONObject(),
        contentState: JSONObject = JSONObject()
    ): TemplateRenderResult = AuctionBidTemplate.render(
        context = contextMock,
        data = flatten(attributes, contentState),
        branding = null,
        smallIcon = 0,
        fallbackTintColor = null
    )!!

    private fun baseAttributes() = JSONObject().apply {
        put("title", "Vintage Camera")
        put("image", "auction_camera")
        put("currencySymbol", "$")
    }

    @Test
    fun render_givenStatusColor_setsAccentAndColorized() {
        val contentState = JSONObject().apply {
            put("currentBid", "1,250")
            put("statusMessage", "You're winning")
            put("statusColor", "#36AE3F")
        }

        val result = render(baseAttributes(), contentState)

        result.accentColor shouldBeEqualTo Color.parseColor("#36AE3F")
        result.colorized.shouldBeTrue()
    }

    @Test
    fun render_noStatusColor_isNotColorized() {
        val contentState = JSONObject().apply {
            put("currentBid", "1,200")
            put("statusMessage", "You've been outbid")
        }

        val result = render(baseAttributes(), contentState)

        result.colorized.shouldBeFalse()
    }

    @Test
    fun render_freeformSlotsRenderedVerbatim() {
        val contentState = JSONObject().apply {
            put("currentBid", "1,200")
            put("subtitle", "47 bids")
            put("statusMessage", "You've been outbid")
        }

        val result = render(baseAttributes(), contentState)

        result.title shouldBeEqualTo "Vintage Camera"
        result.body shouldBeEqualTo "You've been outbid"
        result.subText shouldBeEqualTo "47 bids"
    }

    @Test
    fun render_noSubtitle_subTextIsTypedPrice() {
        val contentState = JSONObject().apply {
            put("currentBid", "1,200")
            put("statusMessage", "Auction live")
        }

        val result = render(baseAttributes(), contentState)

        result.subText shouldBeEqualTo "$1,200"
    }

    @Test
    fun render_defaultsCurrencySymbolToDollar() {
        val attributes = JSONObject().apply {
            put("title", "Vintage Camera")
        }
        val contentState = JSONObject().apply {
            put("currentBid", "100")
            put("statusMessage", "Start")
        }

        val result = render(attributes, contentState)

        result.subText shouldBeEqualTo "$100"
    }

    @Test
    fun render_endTimeNonPositive_countdownUntilIsNull() {
        val contentState = JSONObject().apply {
            put("currentBid", "1")
            put("statusMessage", "x")
            put("endTime", 0L)
        }

        val result = render(baseAttributes(), contentState)

        (result.countdownUntil == null).shouldBeTrue()
    }
}

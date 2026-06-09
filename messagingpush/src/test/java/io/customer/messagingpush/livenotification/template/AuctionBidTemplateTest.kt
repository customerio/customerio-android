package io.customer.messagingpush.livenotification.template

import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [AuctionBidTemplate].
 *
 * Locks the two visual-state branches that templates lean on for state
 * differentiation: high-bidder (green) vs outbid (red), and the user-bid-amount
 * subtext toggle.
 */
@RunWith(RobolectricTestRunner::class)
internal class AuctionBidTemplateTest : IntegrationTest() {

    private val winningGreen = -0xc951c1 // #36AE3F
    private val outbidRed = -0x33ccd0 // #CC3330

    private fun render(
        attributes: JSONObject = JSONObject(),
        contentState: JSONObject = JSONObject()
    ): TemplateRenderResult = AuctionBidTemplate.render(
        context = contextMock,
        attributes = attributes,
        contentState = contentState,
        branding = null,
        smallIcon = 0,
        fallbackTintColor = null
    )

    private fun baseAttributes() = JSONObject().apply {
        put("itemTitle", "Vintage Camera")
        put("itemImageKey", "auction_camera")
        put("currencySymbol", "$")
    }

    @Test
    fun render_userIsHighBidder_setsGreenAccent() {
        val contentState = JSONObject().apply {
            put("currentBid", "1,250")
            put("bidCount", 8)
            put("endTime", 1700000000000L)
            put("statusMessage", "You're winning")
            put("isUserHighBidder", true)
            put("userBidAmount", "1,250")
        }

        val result = render(baseAttributes(), contentState)

        result.accentColor shouldBeEqualTo winningGreen
        result.colorized.shouldBeTrue()
    }

    @Test
    fun render_userIsNotHighBidder_setsRedAccent() {
        val contentState = JSONObject().apply {
            put("currentBid", "1,200")
            put("bidCount", 7)
            put("statusMessage", "You've been outbid")
            put("isUserHighBidder", false)
            put("userBidAmount", "1,150")
        }

        val result = render(baseAttributes(), contentState)

        result.accentColor shouldBeEqualTo outbidRed
    }

    @Test
    fun render_withUserBidAmount_subTextIncludesBidAndCount() {
        val contentState = JSONObject().apply {
            put("currentBid", "1,200")
            put("bidCount", 7)
            put("statusMessage", "You've been outbid")
            put("userBidAmount", "1,150")
        }

        val result = render(baseAttributes(), contentState)

        result.subText shouldBeEqualTo "Your bid: $1,150 · 7 bids"
    }

    @Test
    fun render_withoutUserBidAmount_subTextIsBidCountOnly() {
        val contentState = JSONObject().apply {
            put("currentBid", "1,200")
            put("bidCount", 7)
            put("statusMessage", "Auction live")
        }

        val result = render(baseAttributes(), contentState)

        result.subText shouldBeEqualTo "7 bids"
    }

    @Test
    fun render_emptyUserBidAmount_subTextIsBidCountOnly() {
        // Empty string is treated as absent by the template (takeIf isNotEmpty).
        val contentState = JSONObject().apply {
            put("currentBid", "1,200")
            put("bidCount", 9)
            put("statusMessage", "x")
            put("userBidAmount", "")
        }

        val result = render(baseAttributes(), contentState)

        result.subText shouldBeEqualTo "9 bids"
    }

    @Test
    fun render_defaultsCurrencySymbolToDollar() {
        val attributes = JSONObject().apply {
            put("itemTitle", "Vintage Camera")
        }
        val contentState = JSONObject().apply {
            put("currentBid", "100")
            put("bidCount", 1)
            put("statusMessage", "Start")
        }

        val result = render(attributes, contentState)

        result.body shouldBeEqualTo "Start · $100"
    }

    @Test
    fun render_bodyComposesStatusMessageAndCurrentBid() {
        val contentState = JSONObject().apply {
            put("currentBid", "1,300")
            put("bidCount", 9)
            put("statusMessage", "You've been outbid")
            put("isUserHighBidder", false)
            put("userBidAmount", "1,250")
        }

        val result = render(baseAttributes(), contentState)

        result.body shouldBeEqualTo "You've been outbid · $1,300"
    }

    @Test
    fun render_endTimeNonPositive_countdownUntilIsNull() {
        val contentState = JSONObject().apply {
            put("currentBid", "1")
            put("bidCount", 0)
            put("statusMessage", "x")
            put("endTime", 0L)
        }

        val result = render(baseAttributes(), contentState)

        (result.countdownUntil == null).shouldBeTrue()
    }

    // --- Strict-slotting regression guards ---

    @Test
    fun render_isUserHighBidderInAttributes_isIgnored() {
        val attributes = baseAttributes().apply {
            put("isUserHighBidder", true) // wrong slot
        }
        val contentState = JSONObject().apply {
            put("currentBid", "1,000")
            put("bidCount", 1)
            put("statusMessage", "x")
            // Note: no isUserHighBidder here, so it defaults to false → red.
        }

        val result = render(attributes, contentState)

        result.accentColor shouldBeEqualTo outbidRed
    }

    @Test
    fun render_currencySymbolInContentState_isIgnoredAndDefaultsApply() {
        val attributes = JSONObject().apply {
            put("itemTitle", "x") // no currencySymbol in attributes
        }
        val contentState = JSONObject().apply {
            put("currencySymbol", "€") // wrong slot
            put("currentBid", "1")
            put("bidCount", 1)
            put("statusMessage", "x")
        }

        val result = render(attributes, contentState)

        // The misplaced currency symbol must be ignored; default `$` is applied.
        result.body shouldBeEqualTo "x · $1"
    }
}

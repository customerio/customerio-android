package io.customer.messagingpush.livenotification.template

import android.graphics.Color
import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests [DeliveryTrackingTemplate] rendering against the finalized field
 * contract: freeform slots (`header`/`title`/`subtitle`) are rendered verbatim
 * (never composed) and `statusColor` (hex) drives the accent color.
 *
 * All fields arrive flattened in a single `data` object; the `attributes` /
 * `contentState` grouping in these tests is purely for readability and is merged
 * via [flatten] before rendering.
 */
@RunWith(RobolectricTestRunner::class)
internal class DeliveryTrackingTemplateTest : IntegrationTest() {

    private fun render(
        attributes: JSONObject = JSONObject(),
        contentState: JSONObject = JSONObject()
    ): TemplateRenderResult = DeliveryTrackingTemplate.render(
        context = contextMock,
        data = flatten(attributes, contentState),
        branding = null,
        smallIcon = 0,
        fallbackTintColor = null
    )!!

    @Test
    fun render_givenNoUsableContent_returnsNull() {
        // Required `title` missing: render returns null so the handler skips posting.
        val result = DeliveryTrackingTemplate.render(
            context = contextMock,
            data = JSONObject(),
            branding = null,
            smallIcon = 0,
            fallbackTintColor = null
        )

        result.shouldBeNull()
    }

    @Test
    fun render_givenStaleMessage_bodyShowsStaleMessageVerbatim() {
        val contentState = JSONObject().apply {
            put("title", "Out for delivery")
            put("subtitle", "Driver: Pat")
            put("staleMessage", "Info may be out of date")
        }

        val result = render(contentState = contentState)

        // A stale push shows `staleMessage` verbatim as the body, taking over the status line.
        result.title shouldBeEqualTo "Out for delivery"
        result.body shouldBeEqualTo "Info may be out of date"
    }

    @Test
    fun render_givenAllFields_mapsFreeformSlotsVerbatim() {
        val attributes = JSONObject().apply {
            put("header", "Order #ORD-42")
        }
        val contentState = JSONObject().apply {
            put("title", "Out for delivery")
            put("subtitle", "Driver: Pat")
            put("image", "delivery_truck")
            put("stepCurrent", 2)
            put("stepTotal", 4)
            put("estimatedArrival", 1700000000000L)
        }

        val result = render(attributes, contentState)

        // Freeform slots are rendered verbatim, never composed.
        result.title shouldBeEqualTo "Out for delivery"
        result.body shouldBeEqualTo "Driver: Pat"
        result.subText shouldBeEqualTo "Order #ORD-42"
        result.showProgress.shouldBeTrue()
        result.progress shouldBeEqualTo 2
        result.progressMax shouldBeEqualTo 4
        result.segments.size shouldBeEqualTo 4
        result.countdownUntil shouldBeEqualTo 1700000000000L
    }

    @Test
    fun render_givenNoSubtitleOrHeader_bodyAndSubTextEmpty() {
        val contentState = JSONObject().apply {
            put("title", "Preparing")
            put("stepCurrent", 1)
            put("stepTotal", 3)
        }

        val result = render(contentState = contentState)

        result.title shouldBeEqualTo "Preparing"
        result.body shouldBeEqualTo ""
        result.subText.shouldBeNull()
    }

    @Test
    fun render_givenStatusColor_parsesHexIntoAccent() {
        val contentState = JSONObject().apply {
            put("title", "Delivered")
            put("statusColor", "#36AE3F")
            put("stepTotal", 3)
        }

        val result = render(contentState = contentState)

        result.accentColor shouldBeEqualTo Color.parseColor("#36AE3F")
    }

    @Test
    fun render_stepCurrentIsClampedIntoRange() {
        val contentState = JSONObject().apply {
            put("title", "Anywhere")
            put("stepCurrent", 99)
            put("stepTotal", 4)
        }

        val result = render(contentState = contentState)

        result.progress shouldBeEqualTo 4
    }

    @Test
    fun render_stepCurrentNegative_isClampedToZero() {
        val contentState = JSONObject().apply {
            put("title", "Anywhere")
            put("stepCurrent", -5)
            put("stepTotal", 4)
        }

        val result = render(contentState = contentState)

        result.progress shouldBeEqualTo 0
    }

    @Test
    fun render_stepTotalMissing_defaultsAtLeastToOne() {
        val contentState = JSONObject().apply {
            put("title", "Just placed")
            put("stepCurrent", 0)
        }

        val result = render(contentState = contentState)

        result.progressMax shouldBeEqualTo 1
        result.segments.size shouldBeEqualTo 1
    }

    @Test
    fun render_stepTotalZero_isFlooredToOne() {
        val contentState = JSONObject().apply {
            put("title", "Edge case")
            put("stepCurrent", 0)
            put("stepTotal", 0)
        }

        val result = render(contentState = contentState)

        result.progressMax shouldBeEqualTo 1
        result.segments.size shouldBeEqualTo 1
    }

    @Test
    fun render_estimatedArrivalNonPositive_countdownUntilIsNull() {
        val contentState = JSONObject().apply {
            put("title", "No eta")
            put("estimatedArrival", 0L)
            put("stepTotal", 2)
        }

        val result = render(contentState = contentState)

        result.countdownUntil.shouldBeNull()
    }
}

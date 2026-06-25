package io.customer.messagingpush.livenotification.template

import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests [DeliveryTrackingTemplate] rendering against the documented field schema.
 *
 * All fields arrive flattened in a single `data` object; the legacy
 * `attributes` / `content_state` grouping in these tests is purely for
 * readability and is merged via [flatten] before rendering.
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
        // Payload arrived without the fields the template needs (e.g. content not flattened):
        // render returns null so the handler skips posting instead of showing a blank notification.
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
    fun render_givenAllFields_producesTitleBodySubTextAndProgress() {
        val attributes = JSONObject().apply {
            put("orderId", "ORD-42")
            put("recipientName", "Alex")
        }
        val contentState = JSONObject().apply {
            put("statusMessage", "Out for delivery")
            put("statusImageKey", "delivery_truck")
            put("stepCurrent", 2)
            put("stepTotal", 4)
            put("estimatedArrival", 1700000000000L)
            put("driverName", "Pat")
        }

        val result = render(attributes, contentState)

        result.title shouldBeEqualTo "Delivery for Alex"
        result.body shouldBeEqualTo "Out for delivery"
        result.subText shouldBeEqualTo "Driver: Pat · Order #ORD-42"
        result.showProgress.shouldBeTrue()
        result.progress shouldBeEqualTo 2
        result.progressMax shouldBeEqualTo 4
        result.segments.size shouldBeEqualTo 4
        result.countdownUntil shouldBeEqualTo 1700000000000L
    }

    @Test
    fun render_givenNoRecipientName_fallsBackToOrderTitle() {
        val attributes = JSONObject().apply {
            put("orderId", "ORD-77")
        }
        val contentState = JSONObject().apply {
            put("statusMessage", "Preparing")
            put("stepCurrent", 1)
            put("stepTotal", 3)
        }

        val result = render(attributes, contentState)

        result.title shouldBeEqualTo "Order #ORD-77"
        result.subText shouldBeEqualTo "Order #ORD-77"
    }

    @Test
    fun render_givenNoDriverNoOrderId_subTextIsNull() {
        val contentState = JSONObject().apply {
            put("statusMessage", "On the way")
            put("stepTotal", 3)
        }

        val result = render(contentState = contentState)

        result.subText.shouldBeNull()
    }

    @Test
    fun render_givenDriverButNoOrderId_subTextOmitsOrder() {
        val contentState = JSONObject().apply {
            put("statusMessage", "On the way")
            put("driverName", "Pat")
            put("stepTotal", 3)
        }

        val result = render(contentState = contentState)

        result.subText shouldBeEqualTo "Driver: Pat"
    }

    @Test
    fun render_stepCurrentIsClampedIntoRange() {
        val contentState = JSONObject().apply {
            put("statusMessage", "Anywhere")
            put("stepCurrent", 99)
            put("stepTotal", 4)
        }

        val result = render(contentState = contentState)

        result.progress shouldBeEqualTo 4
    }

    @Test
    fun render_stepCurrentNegative_isClampedToZero() {
        val contentState = JSONObject().apply {
            put("statusMessage", "Anywhere")
            put("stepCurrent", -5)
            put("stepTotal", 4)
        }

        val result = render(contentState = contentState)

        result.progress shouldBeEqualTo 0
    }

    @Test
    fun render_stepTotalMissing_defaultsAtLeastToOne() {
        val contentState = JSONObject().apply {
            put("statusMessage", "Just placed")
            put("stepCurrent", 0)
        }

        val result = render(contentState = contentState)

        result.progressMax shouldBeEqualTo 1
        result.segments.size shouldBeEqualTo 1
    }

    @Test
    fun render_stepTotalZero_isFlooredToOne() {
        val contentState = JSONObject().apply {
            put("statusMessage", "Edge case")
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
            put("statusMessage", "No eta")
            put("estimatedArrival", 0L)
            put("stepTotal", 2)
        }

        val result = render(contentState = contentState)

        result.countdownUntil.shouldBeNull()
    }
}

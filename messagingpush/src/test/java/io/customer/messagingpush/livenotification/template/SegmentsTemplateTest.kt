package io.customer.messagingpush.livenotification.template

import android.graphics.Color
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests [SegmentsTemplate] rendering against the finalized field contract:
 * freeform slots (`header`/`status`/`substatus`) are rendered verbatim (never
 * composed), `segmentsTotal`/`segmentsComplete` are flat integers driving the
 * segmented bar, and styling is branding-only (accent from branding/fallback,
 * no per-push image/statusColor).
 *
 * All fields arrive flattened in a single `data` object; the `attributes` /
 * `contentState` grouping in these tests is purely for readability and is merged
 * via [flatten] before rendering.
 */
@RunWith(RobolectricTestRunner::class)
internal class SegmentsTemplateTest : IntegrationTest() {

    private fun render(
        attributes: JSONObject = JSONObject(),
        contentState: JSONObject = JSONObject(),
        branding: LiveNotificationBranding? = null,
        fallbackTintColor: Int? = null
    ): TemplateRenderResult = SegmentsTemplate.render(
        context = contextMock,
        data = flatten(attributes, contentState),
        branding = branding,
        smallIcon = 0,
        fallbackTintColor = fallbackTintColor
    )!!

    @Test
    fun render_givenNoUsableContent_returnsNull() {
        // Required `status` missing: render returns null so the handler skips posting.
        val result = SegmentsTemplate.render(
            context = contextMock,
            data = JSONObject(),
            branding = null,
            smallIcon = 0,
            fallbackTintColor = null
        )

        result.shouldBeNull()
    }

    @Test
    fun render_givenAllFields_mapsFreeformSlotsVerbatim() {
        val attributes = JSONObject().apply {
            put("header", "Order #ORD-42")
        }
        val contentState = JSONObject().apply {
            put("status", "Out for delivery")
            put("substatus", "Driver: Pat")
            put("segmentsTotal", 4)
            put("segmentsComplete", 2)
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
        // Branding-only: no per-push image, no live timer.
        result.largeIcon.shouldBeNull()
        result.countdownUntil.shouldBeNull()
    }

    @Test
    fun render_givenNoSubstatusButTrailingText_bodyShowsTrailingTextVerbatim() {
        // trailingText has no dedicated Android slot; it fills the otherwise-empty body verbatim.
        val contentState = JSONObject().apply {
            put("status", "Preparing")
            put("segmentsTotal", 3)
            put("segmentsComplete", 1)
            put("trailingText", "5 min")
        }

        val result = render(contentState = contentState)

        result.body shouldBeEqualTo "5 min"
    }

    @Test
    fun render_givenBothSubstatusAndTrailingText_substatusWinsBody() {
        // substatus owns the body line; trailingText is dropped (no distinct slot, never composed).
        val contentState = JSONObject().apply {
            put("status", "Preparing")
            put("substatus", "Almost there")
            put("segmentsTotal", 3)
            put("segmentsComplete", 1)
            put("trailingText", "5 min")
        }

        val result = render(contentState = contentState)

        result.body shouldBeEqualTo "Almost there"
    }

    @Test
    fun render_givenNoSubstatusOrHeaderOrTrailing_bodyEmptyAndSubTextNull() {
        val contentState = JSONObject().apply {
            put("status", "Preparing")
            put("segmentsTotal", 3)
            put("segmentsComplete", 1)
        }

        val result = render(contentState = contentState)

        result.title shouldBeEqualTo "Preparing"
        result.body shouldBeEqualTo ""
        result.subText.shouldBeNull()
    }

    @Test
    fun render_brandingOnly_accentComesFromBranding() {
        val accent = Color.parseColor("#36AE3F")
        val branding = LiveNotificationBranding(
            companyName = "Acme",
            accentColor = accent
        )
        val contentState = JSONObject().apply {
            put("status", "Delivered")
            put("segmentsTotal", 3)
            put("segmentsComplete", 3)
        }

        val result = render(contentState = contentState, branding = branding)

        result.accentColor shouldBeEqualTo accent
    }

    @Test
    fun render_noBranding_fallsBackToTintColor() {
        val fallback = Color.parseColor("#123456")
        val contentState = JSONObject().apply {
            put("status", "Delivered")
            put("segmentsTotal", 3)
            put("segmentsComplete", 3)
        }

        val result = render(contentState = contentState, fallbackTintColor = fallback)

        result.accentColor shouldBeEqualTo fallback
    }

    @Test
    fun render_segmentsCompleteAboveTotal_isClampedToTotal() {
        val contentState = JSONObject().apply {
            put("status", "Anywhere")
            put("segmentsTotal", 4)
            put("segmentsComplete", 99)
        }

        val result = render(contentState = contentState)

        result.progress shouldBeEqualTo 4
    }

    @Test
    fun render_segmentsCompleteNegative_isClampedToZero() {
        val contentState = JSONObject().apply {
            put("status", "Anywhere")
            put("segmentsTotal", 4)
            put("segmentsComplete", -5)
        }

        val result = render(contentState = contentState)

        result.progress shouldBeEqualTo 0
    }

    @Test
    fun render_segmentsTotalMissing_defaultsAtLeastToOne() {
        val contentState = JSONObject().apply {
            put("status", "Just placed")
        }

        val result = render(contentState = contentState)

        result.progressMax shouldBeEqualTo 1
        result.segments.size shouldBeEqualTo 1
    }

    @Test
    fun render_segmentsTotalZero_isFlooredToOne() {
        val contentState = JSONObject().apply {
            put("status", "Edge case")
            put("segmentsTotal", 0)
            put("segmentsComplete", 0)
        }

        val result = render(contentState = contentState)

        result.progressMax shouldBeEqualTo 1
        result.segments.size shouldBeEqualTo 1
    }

    @Test
    fun render_segmentsTotalAboveMax_isCappedAtTwenty() {
        // An untrusted push payload can't blow up the segment allocation.
        val contentState = JSONObject().apply {
            put("status", "Huge payload")
            put("segmentsTotal", 5000)
            put("segmentsComplete", 4000)
        }

        val result = render(contentState = contentState)

        result.progressMax shouldBeEqualTo 20
        result.segments.size shouldBeEqualTo 20
    }
}

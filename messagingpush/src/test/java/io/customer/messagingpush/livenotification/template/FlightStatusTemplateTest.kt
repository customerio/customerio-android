package io.customer.messagingpush.livenotification.template

import android.graphics.Color
import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [FlightStatusTemplate].
 *
 * Exercises:
 * - freeform slots (`title`/`status`/`subtitle`) rendered verbatim, never composed;
 * - `statusColor` (hex) parsed into the accent;
 * - countdown target switching: `progressFraction` present ⇒ estimatedArrival,
 *   absent ⇒ scheduledDeparture.
 *
 * All fields arrive flattened; the grouping is merged via [flatten].
 */
@RunWith(RobolectricTestRunner::class)
internal class FlightStatusTemplateTest : IntegrationTest() {

    private fun render(
        attributes: JSONObject = JSONObject(),
        contentState: JSONObject = JSONObject()
    ): TemplateRenderResult = FlightStatusTemplate.render(
        context = contextMock,
        data = flatten(attributes, contentState),
        branding = null,
        smallIcon = 0,
        fallbackTintColor = null
    )!!

    private fun baseAttributes() = JSONObject().apply {
        put("header", "Flight AA1234")
        put("origin", JSONObject().put("code", "JFK").put("city", "New York"))
        put("destination", JSONObject().put("code", "LAX").put("city", "Los Angeles"))
    }

    @Test
    fun render_happyPath_mapsFreeformSlotsVerbatim() {
        val contentState = JSONObject().apply {
            put("title", "JFK → LAX")
            put("status", "On time")
            put("subtitle", "Gate B12 · Terminal 4")
            put("scheduledDeparture", 1700000000000L)
            put("estimatedArrival", 1700100000000L)
        }

        val result = render(baseAttributes(), contentState)

        result.title shouldBeEqualTo "JFK → LAX"
        result.body shouldBeEqualTo "On time"
        result.subText shouldBeEqualTo "Gate B12 · Terminal 4"
    }

    @Test
    fun render_missingStatusAndSubtitle_bodyEmptySubTextNull() {
        val contentState = JSONObject().apply {
            put("title", "Boarding soon")
            put("scheduledDeparture", 1700000000000L)
        }

        val result = render(baseAttributes(), contentState)

        result.body shouldBeEqualTo ""
        result.subText.shouldBeNull()
    }

    @Test
    fun render_givenStatusColor_parsesHexIntoAccent() {
        val contentState = JSONObject().apply {
            put("title", "Delayed")
            put("status", "Delayed 25 min")
            put("statusColor", "#CC3330")
        }

        val result = render(baseAttributes(), contentState)

        result.accentColor shouldBeEqualTo Color.parseColor("#CC3330")
    }

    // --- Progress / countdown switching ---

    @Test
    fun render_progressFractionPresent_targetsEstimatedArrival() {
        val contentState = JSONObject().apply {
            put("title", "In flight")
            put("scheduledDeparture", 1700000000000L)
            put("estimatedArrival", 1700100000000L)
            put("progressFraction", 0.5)
        }

        val result = render(baseAttributes(), contentState)

        result.showProgress.shouldBeTrue()
        result.progress shouldBeEqualTo 50
        result.progressMax shouldBeEqualTo 100
        result.countdownUntil shouldBeEqualTo 1700100000000L
    }

    @Test
    fun render_progressFractionAbsent_targetsScheduledDeparture() {
        val contentState = JSONObject().apply {
            put("title", "Pre-departure")
            put("scheduledDeparture", 1700000000000L)
            put("estimatedArrival", 1700100000000L)
        }

        val result = render(baseAttributes(), contentState)

        result.showProgress.shouldBeFalse()
        result.countdownUntil shouldBeEqualTo 1700000000000L
    }

    @Test
    fun render_progressFractionOutsideZeroOne_isCoerced() {
        val contentState = JSONObject().apply {
            put("title", "In flight")
            put("estimatedArrival", 1700100000000L)
            put("progressFraction", 2.5) // out of range; spec coerces to [0,1]
        }

        val result = render(baseAttributes(), contentState)

        result.progress shouldBeEqualTo 100
    }
}

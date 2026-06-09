package io.customer.messagingpush.livenotification.template

import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [FlightStatusTemplate].
 *
 * Exercises:
 * - basic title/body/subText composition with origin / destination codes;
 * - the delay-red branch — `delayMinutes > 0` flips the accent and suffixes the body;
 * - countdown target switching: `progressFraction` present ⇒ estimatedArrival,
 *   absent ⇒ scheduledDeparture;
 * - strict slotting — `flightNumber` in `content_state` is ignored.
 */
@RunWith(RobolectricTestRunner::class)
internal class FlightStatusTemplateTest : IntegrationTest() {

    private val delayRed = -0x33ccd0 // #CC3330

    private fun render(
        attributes: JSONObject = JSONObject(),
        contentState: JSONObject = JSONObject()
    ): TemplateRenderResult = FlightStatusTemplate.render(
        context = contextMock,
        attributes = attributes,
        contentState = contentState,
        branding = null,
        smallIcon = 0,
        fallbackTintColor = null
    )

    private fun baseAttributes() = JSONObject().apply {
        put("flightNumber", "AA1234")
        put("origin", JSONObject().put("code", "JFK").put("city", "New York"))
        put("destination", JSONObject().put("code", "LAX").put("city", "Los Angeles"))
    }

    @Test
    fun render_happyPath_composesTitleAndSubText() {
        val contentState = JSONObject().apply {
            put("statusMessage", "On time")
            put("gate", "B12")
            put("terminal", "4")
            put("scheduledDeparture", 1700000000000L)
            put("estimatedArrival", 1700100000000L)
        }

        val result = render(baseAttributes(), contentState)

        result.title shouldBeEqualTo "AA1234 · JFK → LAX"
        result.subText shouldBeEqualTo "Gate B12 · Terminal 4"
        result.body shouldBeEqualTo "On time"
    }

    @Test
    fun render_missingGateAndTerminal_fallsBackToTBA() {
        val contentState = JSONObject().apply {
            put("statusMessage", "Pre-departure")
            put("scheduledDeparture", 1700000000000L)
        }

        val result = render(baseAttributes(), contentState)

        result.subText shouldBeEqualTo "Gate TBA · Terminal TBA"
    }

    // --- Delay-red decision branch ---

    @Test
    fun render_delayMinutesPositive_setsRedAccentAndAppendsBody() {
        val contentState = JSONObject().apply {
            put("statusMessage", "Boarding")
            put("delayMinutes", 25)
        }

        val result = render(baseAttributes(), contentState)

        result.accentColor shouldBeEqualTo delayRed
        result.body shouldBeEqualTo "Boarding · Delayed 25 min"
    }

    @Test
    fun render_delayMinutesZero_doesNotForceRed() {
        val contentState = JSONObject().apply {
            put("statusMessage", "On time")
            put("delayMinutes", 0)
        }

        val result = render(baseAttributes(), contentState)

        (result.accentColor == delayRed).shouldBeFalse()
        result.body shouldBeEqualTo "On time"
    }

    // --- Progress / countdown switching ---

    @Test
    fun render_progressFractionPresent_targetsEstimatedArrival() {
        val contentState = JSONObject().apply {
            put("statusMessage", "In flight")
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
            put("statusMessage", "Pre-departure")
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
            put("statusMessage", "In flight")
            put("estimatedArrival", 1700100000000L)
            put("progressFraction", 2.5) // out of range; spec coerces to [0,1]
        }

        val result = render(baseAttributes(), contentState)

        result.progress shouldBeEqualTo 100
    }

    // --- Strict-slotting regression guard ---

    @Test
    fun render_flightNumberInContentState_isIgnored() {
        val attributes = JSONObject().apply {
            put("origin", JSONObject().put("code", "JFK"))
            put("destination", JSONObject().put("code", "LAX"))
        }
        val contentState = JSONObject().apply {
            put("flightNumber", "MISPLACED")
            put("statusMessage", "x")
        }

        val result = render(attributes, contentState)

        result.title shouldBeEqualTo " · JFK → LAX" // flightNumber empty, prefix space remains
    }

    @Test
    fun render_delayMinutesInAttributes_isIgnored() {
        val attributes = baseAttributes().apply {
            put("delayMinutes", 25) // wrong slot
        }
        val contentState = JSONObject().apply {
            put("statusMessage", "x")
        }

        val result = render(attributes, contentState)

        (result.accentColor == delayRed).shouldBeFalse()
        result.body shouldBeEqualTo "x"
    }
}

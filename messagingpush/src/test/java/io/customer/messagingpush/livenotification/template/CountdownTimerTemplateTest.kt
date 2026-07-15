package io.customer.messagingpush.livenotification.template

import android.graphics.Color
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [CountdownTimerTemplate] against the iOS `CIOCountdownTimerAttributes`
 * field model.
 *
 * Freeform slots (`header`/`title`/`statusMessage`) are rendered verbatim.
 * Exercises the countdown branches:
 * - future `endTime` ⇒ live chronometer (countdownUntil = endTime);
 * - absent/past `endTime` ⇒ no chronometer (push-driven finished state), showing
 *   `title` + `statusMessage`.
 * Styling is branding-only (accent from branding/fallback, no per-push image).
 */
@RunWith(RobolectricTestRunner::class)
internal class CountdownTimerTemplateTest : IntegrationTest() {

    private fun render(
        attributes: JSONObject = JSONObject(),
        contentState: JSONObject = JSONObject(),
        branding: LiveNotificationBranding? = null,
        fallbackTintColor: Int? = null
    ): TemplateRenderResult = CountdownTimerTemplate.render(
        context = contextMock,
        data = flatten(attributes, contentState),
        branding = branding,
        smallIcon = 0,
        fallbackTintColor = fallbackTintColor
    )!!

    @Test
    fun render_givenNoUsableContent_returnsNull() {
        // Required `title` missing: render returns null so the handler skips posting.
        val result = CountdownTimerTemplate.render(
            context = contextMock,
            data = JSONObject(),
            branding = null,
            smallIcon = 0,
            fallbackTintColor = null
        )

        result.shouldBeNull()
    }

    @Test
    fun render_futureEndTime_setsCountdownAndStatusMessageBody() {
        // endTime is epoch SECONDS on the wire; the template converts to millis.
        val futureSeconds = System.currentTimeMillis() / 1000 + 60L
        val attributes = JSONObject().apply { put("header", "Limited time") }
        val contentState = JSONObject().apply {
            put("title", "Flash sale ends in")
            put("statusMessage", "Hurry!")
            put("endTime", futureSeconds)
        }

        val result = render(attributes, contentState)

        result.title shouldBeEqualTo "Flash sale ends in"
        result.body shouldBeEqualTo "Hurry!"
        result.subText shouldBeEqualTo "Limited time"
        result.countdownUntil shouldBeEqualTo futureSeconds * 1000L
    }

    @Test
    fun render_pastEndTime_showsFinishedStateWithNoChronometer() {
        val pastSeconds = System.currentTimeMillis() / 1000 - 60L
        val contentState = JSONObject().apply {
            put("title", "Sale is live!")
            put("statusMessage", "Shop now")
            put("endTime", pastSeconds)
        }

        val result = render(contentState = contentState)

        result.title shouldBeEqualTo "Sale is live!"
        result.body shouldBeEqualTo "Shop now"
        result.countdownUntil.shouldBeNull()
    }

    @Test
    fun render_endTimeAbsent_showsFinishedStateWithNoChronometer() {
        // Push-driven finished state: a new content-state with a "done" title and no endTime.
        val contentState = JSONObject().apply {
            put("title", "Done")
            put("statusMessage", "Thanks for shopping")
        }

        val result = render(contentState = contentState)

        result.title shouldBeEqualTo "Done"
        result.body shouldBeEqualTo "Thanks for shopping"
        result.countdownUntil.shouldBeNull()
    }

    @Test
    fun render_brandingOnly_accentAndNoImage() {
        val accent = Color.parseColor("#1B5E20")
        val branding = LiveNotificationBranding(companyName = "Acme", accentColor = accent)
        val contentState = JSONObject().apply {
            put("title", "Flash sale ends in")
            put("endTime", System.currentTimeMillis() / 1000 + 60L)
        }

        val result = render(contentState = contentState, branding = branding)

        result.accentColor shouldBeEqualTo accent
        // Branding-only: no per-push image; the handler fills the large icon from branding.
        result.largeIcon.shouldBeNull()
    }

    @Test
    fun render_noStatusMessage_bodyEmpty() {
        val contentState = JSONObject().apply {
            put("title", "Flash sale ends in")
            put("endTime", System.currentTimeMillis() / 1000 + 60L)
        }

        val result = render(contentState = contentState)

        result.body shouldBeEqualTo ""
    }
}

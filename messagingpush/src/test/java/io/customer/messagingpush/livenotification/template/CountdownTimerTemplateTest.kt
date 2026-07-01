package io.customer.messagingpush.livenotification.template

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
 * Tests for [CountdownTimerTemplate].
 *
 * Exercises the three decision branches:
 * - pre-target (`now < targetDate`) ⇒ body = `statusMessage`, countdownUntil = targetDate;
 * - post-target with `expiredMessage` ⇒ body = `expiredMessage`, countdownUntil = null;
 * - post-target without `expiredMessage` ⇒ `cancelImmediately = true` so the
 *   handler can dismiss the activity rather than render a stale countdown.
 */
@RunWith(RobolectricTestRunner::class)
internal class CountdownTimerTemplateTest : IntegrationTest() {

    private fun render(
        attributes: JSONObject = JSONObject(),
        contentState: JSONObject = JSONObject()
    ): TemplateRenderResult = CountdownTimerTemplate.render(
        context = contextMock,
        data = flatten(attributes, contentState),
        branding = null,
        smallIcon = 0,
        fallbackTintColor = null
    )

    private fun titleAttributes(title: String = "Flash Sale") = JSONObject().apply {
        put("title", title)
        put("heroImageKey", "flash_sale_hero")
    }

    @Test
    fun render_preTarget_setsCountdownAndStatusBody() {
        val future = System.currentTimeMillis() + 60_000L
        val contentState = JSONObject().apply {
            put("targetDate", future)
            put("statusMessage", "Sale starts in")
        }

        val result = render(titleAttributes(), contentState)

        result.title shouldBeEqualTo "Flash Sale"
        result.body shouldBeEqualTo "Sale starts in"
        result.countdownUntil shouldBeEqualTo future
        result.cancelImmediately.shouldBeFalse()
    }

    @Test
    fun render_postTargetWithExpiredMessage_swapsBodyAndClearsCountdown() {
        val past = System.currentTimeMillis() - 60_000L
        val contentState = JSONObject().apply {
            put("targetDate", past)
            put("statusMessage", "Sale starts in")
            put("expiredMessage", "Sale is live!")
        }

        val result = render(titleAttributes(), contentState)

        result.body shouldBeEqualTo "Sale is live!"
        result.countdownUntil.shouldBeNull()
        result.cancelImmediately.shouldBeFalse()
    }

    @Test
    fun render_postTargetWithoutExpiredMessage_flagsCancelImmediately() {
        val past = System.currentTimeMillis() - 60_000L
        val contentState = JSONObject().apply {
            put("targetDate", past)
            put("statusMessage", "Sale starts in")
        }

        val result = render(titleAttributes(), contentState)

        result.cancelImmediately.shouldBeTrue()
        // When cancelImmediately = true, the rest of the result is irrelevant because
        // the handler short-circuits before rendering. We assert nothing else here.
    }

    @Test
    fun render_targetDateAbsent_isTreatedAsPreTarget() {
        // Spec: targetDate is required. Lenient parsing — missing targetDate must not
        // crash; should render with countdownUntil = null and body = statusMessage.
        val contentState = JSONObject().apply {
            put("statusMessage", "Sale starts in")
        }

        val result = render(titleAttributes(), contentState)

        result.body shouldBeEqualTo "Sale starts in"
        result.countdownUntil.shouldBeNull()
        result.cancelImmediately.shouldBeFalse()
    }

    @Test
    fun render_emptyHeroImageKey_returnsNullLargeIcon() {
        val attributes = JSONObject().apply {
            put("title", "x")
        }
        val contentState = JSONObject().apply {
            put("statusMessage", "x")
        }

        val result = render(attributes, contentState)

        result.largeIcon.shouldBeNull()
    }
}

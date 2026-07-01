package io.customer.messagingpush.livenotification.template

import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [LiveScoreTemplate].
 *
 * Exercises the body composition fallback chain:
 * `statusMessage` overrides ⇒ `period · clock` ⇒ bare `period`.
 *
 * All fields arrive flattened; the legacy grouping is merged via [flatten].
 */
@RunWith(RobolectricTestRunner::class)
internal class LiveScoreTemplateTest : IntegrationTest() {

    private fun render(
        attributes: JSONObject = JSONObject(),
        contentState: JSONObject = JSONObject()
    ): TemplateRenderResult = LiveScoreTemplate.render(
        context = contextMock,
        data = flatten(attributes, contentState),
        branding = null,
        smallIcon = 0,
        fallbackTintColor = null
    )!!

    private fun teamsAttributes() = JSONObject().apply {
        put("homeTeam", JSONObject().put("name", "Lakers"))
        put("awayTeam", JSONObject().put("name", "Celtics"))
        put("sport", "basketball")
    }

    @Test
    fun render_givenScoresAndClock_composesTitleAndPeriodClockBody() {
        val contentState = JSONObject().apply {
            put("homeScore", 14)
            put("awayScore", 7)
            put("period", "2nd Quarter")
            put("clock", "5:30")
        }

        val result = render(teamsAttributes(), contentState)

        result.title shouldBeEqualTo "Lakers 14 - 7 Celtics"
        result.body shouldBeEqualTo "2nd Quarter · 5:30"
    }

    @Test
    fun render_givenStatusMessage_overridesBody() {
        val contentState = JSONObject().apply {
            put("homeScore", 0)
            put("awayScore", 0)
            put("period", "Halftime")
            put("clock", "0:00")
            put("statusMessage", "Half time show")
        }

        val result = render(teamsAttributes(), contentState)

        result.body shouldBeEqualTo "Half time show"
    }

    @Test
    fun render_clockAbsent_fallsBackToBarePeriod() {
        val contentState = JSONObject().apply {
            put("homeScore", 28)
            put("awayScore", 24)
            put("period", "FT")
        }

        val result = render(teamsAttributes(), contentState)

        result.body shouldBeEqualTo "FT"
    }

    @Test
    fun render_emptyClockString_isTreatedAsAbsent() {
        // Existing template logic treats empty-string clock as "no clock" (takeIf isNotEmpty).
        val contentState = JSONObject().apply {
            put("homeScore", 1)
            put("awayScore", 1)
            put("period", "1st")
            put("clock", "")
        }

        val result = render(teamsAttributes(), contentState)

        result.body shouldBeEqualTo "1st"
    }

    @Test
    fun render_noLeagueLogo_fallsBackToTeamLogo() {
        // Single native image slot: with no league logo, a team logo fills it.
        ModuleMessagingPushFCM(
            MessagingPushModuleConfig.Builder()
                .registerLiveNotificationAsset("home-logo", byteArrayOf(1, 2, 3))
                .build()
        ).attachToSDKComponent()
        val attributes = JSONObject().apply {
            put("homeTeam", JSONObject().put("name", "Lakers").put("logoKey", "home-logo"))
            put("awayTeam", JSONObject().put("name", "Celtics"))
        }

        val result = render(attributes, JSONObject().put("period", "1st"))

        result.largeIcon.shouldNotBeNull()
    }

    @Test
    fun render_noLogosAtAll_largeIconIsNull() {
        val result = render(teamsAttributes(), JSONObject().put("period", "1st"))

        result.largeIcon.shouldBeNull()
    }

    @Test
    fun render_emptyTeamNames_defaultsToEmptyStrings() {
        val attributes = JSONObject().apply {
            put("homeTeam", JSONObject())
            put("awayTeam", JSONObject())
        }
        val contentState = JSONObject().apply {
            put("homeScore", 3)
            put("awayScore", 0)
            put("period", "1st")
        }

        val result = render(attributes, contentState)

        result.title shouldBeEqualTo " 3 - 0 "
    }
}

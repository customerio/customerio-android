package io.customer.messagingpush.livenotification.template

import android.graphics.Color
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
 * Team names and scores are typed/structured fields (title = matchup, subText =
 * numeric score); the freeform `subtitle` bottom-label is rendered verbatim as
 * the body. `statusColor` (hex) drives the accent.
 *
 * All fields arrive flattened; the grouping is merged via [flatten].
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
    }

    @Test
    fun render_givenScores_titleIsMatchupAndSubTextIsScore() {
        val contentState = JSONObject().apply {
            put("homeScore", 14)
            put("awayScore", 7)
        }

        val result = render(teamsAttributes(), contentState)

        result.title shouldBeEqualTo "Lakers - Celtics"
        result.subText shouldBeEqualTo "14–7"
    }

    @Test
    fun render_givenSubtitle_rendersVerbatimAsBody() {
        val contentState = JSONObject().apply {
            put("homeScore", 0)
            put("awayScore", 0)
            put("subtitle", "2nd half · 55:67")
        }

        val result = render(teamsAttributes(), contentState)

        result.body shouldBeEqualTo "2nd half · 55:67"
    }

    @Test
    fun render_noScores_subTextIsNull() {
        val contentState = JSONObject().apply {
            put("subtitle", "Starts in 15 Min")
        }

        val result = render(teamsAttributes(), contentState)

        result.subText.shouldBeNull()
        result.body shouldBeEqualTo "Starts in 15 Min"
    }

    @Test
    fun render_givenStatusColor_parsesHexIntoAccent() {
        val contentState = JSONObject().apply {
            put("homeScore", 3)
            put("awayScore", 1)
            put("statusColor", "#36AE3F")
        }

        val result = render(teamsAttributes(), contentState)

        result.accentColor shouldBeEqualTo Color.parseColor("#36AE3F")
    }

    @Test
    fun render_noImage_fallsBackToTeamLogo() {
        // Single native image slot: with no league/app image, a team logo fills it.
        ModuleMessagingPushFCM(
            MessagingPushModuleConfig.Builder()
                .registerLiveNotificationAsset("home-logo", byteArrayOf(1, 2, 3))
                .build()
        ).attachToSDKComponent()
        val attributes = JSONObject().apply {
            put("homeTeam", JSONObject().put("name", "Lakers").put("logo", "home-logo"))
            put("awayTeam", JSONObject().put("name", "Celtics"))
        }

        val result = render(attributes, JSONObject().put("homeScore", 1))

        result.largeIcon.shouldNotBeNull()
    }

    @Test
    fun render_noLogosAtAll_largeIconIsNull() {
        val result = render(teamsAttributes(), JSONObject().put("homeScore", 1))

        result.largeIcon.shouldBeNull()
    }

    @Test
    fun render_onlyHomeTeamName_titleOmitsMissingSide() {
        val attributes = JSONObject().apply {
            put("homeTeam", JSONObject().put("name", "Lakers"))
            put("awayTeam", JSONObject())
        }

        val result = render(attributes, JSONObject().put("homeScore", 3).put("awayScore", 0))

        result.title shouldBeEqualTo "Lakers"
        result.subText shouldBeEqualTo "3–0"
    }
}

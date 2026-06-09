package io.customer.messagingpush.livenotification.template

import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
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
 * Also locks the strict-slotting decision — `homeScore` in `attributes` is ignored.
 */
@RunWith(RobolectricTestRunner::class)
internal class LiveScoreTemplateTest : IntegrationTest() {

    private fun render(
        attributes: JSONObject = JSONObject(),
        contentState: JSONObject = JSONObject()
    ): TemplateRenderResult = LiveScoreTemplate.render(
        context = contextMock,
        attributes = attributes,
        contentState = contentState,
        branding = null,
        smallIcon = 0,
        fallbackTintColor = null
    )

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

    // --- Strict-slotting regression guards ---

    @Test
    fun render_homeScoreInAttributes_isIgnored() {
        val attributes = teamsAttributes().apply {
            put("homeScore", 99) // wrong slot
        }
        val contentState = JSONObject().apply {
            put("awayScore", 7)
            put("period", "1st")
        }

        val result = render(attributes, contentState)

        // homeScore defaults to 0 in content_state, because the misplaced value must be ignored.
        result.title shouldBeEqualTo "Lakers 0 - 7 Celtics"
    }

    @Test
    fun render_leagueLogoKeyInContentState_isIgnored() {
        // leagueLogoKey belongs in `attributes` per the spec. Misplaced should not resolve.
        val attributes = teamsAttributes()
        val contentState = JSONObject().apply {
            put("homeScore", 0)
            put("awayScore", 0)
            put("period", "1st")
            put("leagueLogoKey", "league_nba")
        }

        val result = render(attributes, contentState)

        // largeIcon should be null because the misplaced key was not consulted.
        // (No host-app drawable was registered for "league_nba" anyway, but the contract
        // we lock here is "don't even attempt to resolve from content_state".)
        // We can't observe `attempted` directly; we can observe the slot still produces null.
        result.largeIcon shouldBeEqualTo null
    }
}

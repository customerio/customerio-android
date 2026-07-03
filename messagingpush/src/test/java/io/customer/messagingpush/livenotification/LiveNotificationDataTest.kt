package io.customer.messagingpush.livenotification

import io.customer.messagingpush.livenotification.template.TemplateRegistry
import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class LiveNotificationDataTest : IntegrationTest() {

    @Test
    fun deliveryTracking_mapsActivityTypeAndScalarFields() {
        val data = LiveNotificationData.DeliveryTracking(
            title = "On the way",
            header = "Order update",
            stepCurrent = 1,
            stepTotal = 3
        )

        data.activityType shouldBeEqualTo TemplateRegistry.DELIVERY_TRACKING
        val fields = data.fields()
        fields["title"] shouldBeEqualTo "On the way"
        fields["header"] shouldBeEqualTo "Order update"
        fields["stepCurrent"] shouldBeEqualTo 1
        fields["stepTotal"] shouldBeEqualTo 3
        // Unset optional fields are present as null; the manager omits them from the envelope.
        fields["subtitle"].shouldBeNull()
    }

    @Test
    fun deliveryTracking_splitsStaticAttributesFromDynamicContentState() {
        val data = LiveNotificationData.DeliveryTracking(
            title = "On the way",
            header = "Order update",
            subtitle = "For Alex",
            stepCurrent = 1,
            stepTotal = 3,
            statusColor = "#36AE3F"
        )

        // Static (attributes): header only.
        data.attributes().containsKey("header").shouldBeTrue()
        data.attributes().containsKey("title").shouldBeFalse()

        // Dynamic (contentState): title, subtitle, progress, statusColor, etc.
        val contentState = data.contentState()
        contentState["title"] shouldBeEqualTo "On the way"
        contentState["subtitle"] shouldBeEqualTo "For Alex"
        contentState["stepCurrent"] shouldBeEqualTo 1
        contentState["statusColor"] shouldBeEqualTo "#36AE3F"
        contentState.containsKey("header").shouldBeFalse()
    }

    @Test
    fun flightStatus_nestedAirportsSerializeToJsonInAttributes() {
        val data = LiveNotificationData.FlightStatus(
            title = "On time",
            origin = LiveNotificationData.Airport("JFK", "New York"),
            destination = LiveNotificationData.Airport("LAX")
        )

        data.activityType shouldBeEqualTo TemplateRegistry.FLIGHT_STATUS

        // Airports are static, so they live in attributes.
        val origin = data.attributes()["origin"] as JSONObject
        origin.getString("code") shouldBeEqualTo "JFK"
        origin.getString("city") shouldBeEqualTo "New York"

        val destination = data.attributes()["destination"] as JSONObject
        destination.getString("code") shouldBeEqualTo "LAX"
        // city omitted when not provided.
        destination.has("city").shouldBeFalse()

        // title is dynamic.
        data.contentState()["title"] shouldBeEqualTo "On time"
    }

    @Test
    fun liveScore_teamLogoSerializesUnderLogoKey() {
        val data = LiveNotificationData.LiveScore(
            homeTeam = LiveNotificationData.Team("Lakers", logo = "lakers_logo"),
            awayTeam = LiveNotificationData.Team("Celtics"),
            homeScore = 14,
            awayScore = 7
        )

        val home = data.attributes()["homeTeam"] as JSONObject
        home.getString("name") shouldBeEqualTo "Lakers"
        home.getString("logo") shouldBeEqualTo "lakers_logo"

        // Scores are dynamic.
        data.contentState()["homeScore"] shouldBeEqualTo 14
        data.contentState()["awayScore"] shouldBeEqualTo 7
    }
}

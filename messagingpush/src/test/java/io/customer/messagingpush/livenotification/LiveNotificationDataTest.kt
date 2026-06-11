package io.customer.messagingpush.livenotification

import io.customer.messagingpush.livenotification.template.TemplateRegistry
import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class LiveNotificationDataTest : IntegrationTest() {

    @Test
    fun deliveryTracking_mapsActivityTypeAndScalarFields() {
        val data = LiveNotificationData.DeliveryTracking(
            orderId = "A-1",
            statusMessage = "On the way",
            stepCurrent = 1,
            stepTotal = 3
        )

        data.activityType shouldBeEqualTo TemplateRegistry.DELIVERY_TRACKING
        val fields = data.fields()
        fields["orderId"] shouldBeEqualTo "A-1"
        fields["statusMessage"] shouldBeEqualTo "On the way"
        fields["stepCurrent"] shouldBeEqualTo 1
        fields["stepTotal"] shouldBeEqualTo 3
        // Unset optional fields are present as null; the manager omits them from the envelope.
        fields["recipientName"].shouldBeNull()
    }

    @Test
    fun flightStatus_nestedAirportsSerializeToJson() {
        val data = LiveNotificationData.FlightStatus(
            flightNumber = "AA1",
            origin = LiveNotificationData.Airport("JFK", "New York"),
            destination = LiveNotificationData.Airport("LAX"),
            statusMessage = "On time"
        )

        data.activityType shouldBeEqualTo TemplateRegistry.FLIGHT_STATUS

        val origin = data.fields()["origin"] as JSONObject
        origin.getString("code") shouldBeEqualTo "JFK"
        origin.getString("city") shouldBeEqualTo "New York"

        val destination = data.fields()["destination"] as JSONObject
        destination.getString("code") shouldBeEqualTo "LAX"
        // city omitted when not provided.
        destination.has("city").shouldBeFalse()
    }
}

package io.customer.location.geofence.store

import io.customer.sdk.communication.Event
import kotlinx.serialization.json.Json
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotContain
import org.junit.Test

class PendingGeofenceDeliveryTest {

    @Test
    fun key_expectGeofenceIdTransitionTimestampComposite() {
        val entry = PendingGeofenceDelivery("biz-1", Event.GeofenceTransition.ENTER, 1.0, 2.0, 1_234L)

        // Doubles as the WorkManager unique-work name so the flush can cancel by key.
        entry.key shouldBeEqualTo "biz-1_ENTER_1234"
    }

    @Test
    fun serialization_givenRoundTrip_expectEqualEntry() {
        val entry = PendingGeofenceDelivery("biz-2", Event.GeofenceTransition.EXIT, 37.7749, -122.4194, 99L)

        val json = Json.encodeToString(PendingGeofenceDelivery.serializer(), entry)
        val restored = Json.decodeFromString(PendingGeofenceDelivery.serializer(), json)

        restored shouldBeEqualTo entry
    }

    @Test
    fun serialization_givenNullLatLng_expectRoundTripPreservesNulls() {
        val entry = PendingGeofenceDelivery("biz-3", Event.GeofenceTransition.ENTER, null, null, 0L)

        val json = Json.encodeToString(PendingGeofenceDelivery.serializer(), entry)
        val restored = Json.decodeFromString(PendingGeofenceDelivery.serializer(), json)

        restored shouldBeEqualTo entry
        restored.latitude shouldBeEqualTo null
        restored.longitude shouldBeEqualTo null
    }

    @Test
    fun toEventProperties_givenLatLng_expectAllPropertiesPresent() {
        val entry = PendingGeofenceDelivery("biz-4", Event.GeofenceTransition.ENTER, 1.5, 2.5, 50L)

        val props = entry.toEventProperties()

        props["geofence_id"] shouldBeEqualTo "biz-4"
        props["transition_type"] shouldBeEqualTo "enter"
        props["latitude"] shouldBeEqualTo 1.5
        props["longitude"] shouldBeEqualTo 2.5
        props["timestamp"] shouldBeEqualTo 50L
    }

    @Test
    fun toEventProperties_givenNullLatLng_expectLatLngOmitted() {
        val entry = PendingGeofenceDelivery("biz-5", Event.GeofenceTransition.EXIT, null, null, 7L)

        val props = entry.toEventProperties()

        props.keys shouldNotContain "latitude"
        props.keys shouldNotContain "longitude"
        props.keys shouldContain "geofence_id"
        props["transition_type"] shouldBeEqualTo "exit"
    }
}

package io.customer.geofence.store

import io.customer.sdk.communication.Event
import java.util.Date
import kotlinx.serialization.json.Json
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotContain
import org.junit.Test

class PendingGeofenceDeliveryTest {

    @Test
    fun key_expectGeofenceIdTransitionTimestampComposite() {
        val entry = PendingGeofenceDelivery("biz-1", Event.GeofenceTransition.ENTER, 1_234L, "user-A")

        // Doubles as the WorkManager unique-work name so the flush can cancel by key.
        entry.key shouldBeEqualTo "biz-1_ENTER_1234"
    }

    @Test
    fun serialization_givenRoundTrip_expectEqualEntry() {
        val entry = PendingGeofenceDelivery("biz-2", Event.GeofenceTransition.EXIT, 99L, "user-A")

        val json = Json.encodeToString(PendingGeofenceDelivery.serializer(), entry)
        val restored = Json.decodeFromString(PendingGeofenceDelivery.serializer(), json)

        restored shouldBeEqualTo entry
    }

    @Test
    fun serialization_givenUserIdSnapshot_expectRoundTripPreservesIt() {
        val entry = PendingGeofenceDelivery("biz-u", Event.GeofenceTransition.ENTER, 3L, "user-A")

        val json = Json.encodeToString(PendingGeofenceDelivery.serializer(), entry)
        val restored = Json.decodeFromString(PendingGeofenceDelivery.serializer(), json)

        restored.userId shouldBeEqualTo "user-A"
    }

    @Test
    fun serialization_givenNullUserId_expectRoundTripPreservesNull() {
        // Anonymous entries are queued by the receiver for the foreground flush.
        val entry = PendingGeofenceDelivery("biz-anon", Event.GeofenceTransition.ENTER, 5L, userId = null)

        val json = Json.encodeToString(PendingGeofenceDelivery.serializer(), entry)
        val restored = Json.decodeFromString(PendingGeofenceDelivery.serializer(), json)

        restored shouldBeEqualTo entry
        restored.userId shouldBeEqualTo null
    }

    @Test
    fun toEventProperties_expectOnlyGeofenceTransitionAndTimestamp() {
        val entry = PendingGeofenceDelivery("biz-4", Event.GeofenceTransition.ENTER, 50L, "user-A")

        val props = entry.toEventProperties()

        props["geofence_id"] shouldBeEqualTo "biz-4"
        props["transition_type"] shouldBeEqualTo "enter"
        props["timestamp"] shouldBeEqualTo 50L
        props.keys shouldNotContain "latitude"
        props.keys shouldNotContain "longitude"
    }

    @Test
    fun toGeofenceTransitionEvent_givenSecondsTimestamp_expectEventTimestampInMillis() {
        // `timestamp` is unix seconds; `Event.timestamp` is a `Date` (millis).
        // The conversion lives on the data class so no caller can hand-roll
        // `Date(entry.timestamp)` and silently produce a January 1970 instant.
        val entry = PendingGeofenceDelivery("biz-t", Event.GeofenceTransition.ENTER, 1_700_000_000L, "user-A")

        val event = entry.toGeofenceTransitionEvent()

        event.timestamp shouldBeEqualTo Date(1_700_000_000_000L)
        event.geofenceId shouldBeEqualTo "biz-t"
        event.transition shouldBeEqualTo Event.GeofenceTransition.ENTER
        event.userId shouldBeEqualTo "user-A"
    }
}

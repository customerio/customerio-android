package io.customer.geofence.store

import io.customer.geofence.GeofenceRegion
import io.customer.sdk.communication.Event
import java.util.Date
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotContain
import org.junit.Test

class PendingGeofenceDeliveryTest {

    @Test
    fun key_givenNoGeoset_expectNoneSuffix() {
        val entry = PendingGeofenceDelivery("biz-1", Event.GeofenceTransition.ENTER, 1_234L, "user-A", transitionId = "tid-1")

        // Doubles as the WorkManager unique-work name so the flush can cancel by key. The stable
        // transition ID keeps distinct crossings separate even when they happen in the same second.
        entry.key shouldBeEqualTo "biz-1_ENTER_tid-1_none"
    }

    @Test
    fun key_givenGeoset_expectGeosetInKey() {
        // The per-geoset fan-out shares a transition ID; the geoset keeps keys
        // distinct so the entries don't overwrite each other in the store / WorkManager.
        val enter7 = PendingGeofenceDelivery("biz-1", Event.GeofenceTransition.ENTER, 1_234L, "user-A", transitionId = "tid-1", geosetId = "7")
        val enter8 = enter7.copy(geosetId = "8")

        enter7.key shouldBeEqualTo "biz-1_ENTER_tid-1_7"
        enter8.key shouldBeEqualTo "biz-1_ENTER_tid-1_8"
    }

    @Test
    fun key_givenDistinctCrossingsInSameSecond_expectDistinctKeys() {
        val first = PendingGeofenceDelivery(
            "biz-1",
            Event.GeofenceTransition.ENTER,
            1_234L,
            "user-A",
            transitionId = "tid-1"
        )
        val second = first.copy(transitionId = "tid-2")

        first.key shouldBeEqualTo "biz-1_ENTER_tid-1_none"
        second.key shouldBeEqualTo "biz-1_ENTER_tid-2_none"
    }

    @Test
    fun serialization_givenRoundTrip_expectEqualEntry() {
        val entry = PendingGeofenceDelivery("biz-2", Event.GeofenceTransition.EXIT, 99L, "user-A", transitionId = "tid-2")

        val json = Json.encodeToString(PendingGeofenceDelivery.serializer(), entry)
        val restored = Json.decodeFromString(PendingGeofenceDelivery.serializer(), json)

        restored shouldBeEqualTo entry
        // The minted id must survive persistence so retries reuse it.
        restored.transitionId shouldBeEqualTo "tid-2"
    }

    @Test
    fun serialization_givenUserIdSnapshot_expectRoundTripPreservesIt() {
        val entry = PendingGeofenceDelivery("biz-u", Event.GeofenceTransition.ENTER, 3L, "user-A", transitionId = "tid-u")

        val json = Json.encodeToString(PendingGeofenceDelivery.serializer(), entry)
        val restored = Json.decodeFromString(PendingGeofenceDelivery.serializer(), json)

        restored.userId shouldBeEqualTo "user-A"
    }

    @Test
    fun serialization_givenNullUserId_expectRoundTripPreservesNull() {
        // Anonymous entries are queued by the receiver for the foreground flush.
        val entry = PendingGeofenceDelivery("biz-anon", Event.GeofenceTransition.ENTER, 5L, userId = null, transitionId = "tid-anon")

        val json = Json.encodeToString(PendingGeofenceDelivery.serializer(), entry)
        val restored = Json.decodeFromString(PendingGeofenceDelivery.serializer(), json)

        restored shouldBeEqualTo entry
        restored.userId shouldBeEqualTo null
    }

    @Test
    fun toEventProperties_expectTransitionGeofenceIdAndTransitionIdNoTimestamp() {
        val entry = PendingGeofenceDelivery("biz-4", Event.GeofenceTransition.ENTER, 50L, "user-A", transitionId = "tid-4")

        val props = entry.toEventProperties()

        props["geofenceId"] shouldBeEqualTo "biz-4"
        props["transition"] shouldBeEqualTo "enter"
        props["transitionId"] shouldBeEqualTo "tid-4"
        // Timestamp rides the event envelope, not the properties.
        props.keys shouldNotContain "timestamp"
        props.keys shouldNotContain "latitude"
        props.keys shouldNotContain "longitude"
    }

    @Test
    fun toEventProperties_givenGeofenceName_expectNamePresent() {
        val entry = PendingGeofenceDelivery("biz-5", Event.GeofenceTransition.ENTER, 50L, "user-A", transitionId = "tid-5", geofenceName = "Ferry Building")

        entry.toEventProperties()["geofenceName"] shouldBeEqualTo "Ferry Building"
    }

    @Test
    fun toEventProperties_givenNullGeofenceName_expectNameOmitted() {
        // Region not in the cached set => omit the property rather than send a synthetic value.
        val entry = PendingGeofenceDelivery("biz-6", Event.GeofenceTransition.ENTER, 50L, "user-A", transitionId = "tid-6", geofenceName = null)

        entry.toEventProperties().keys shouldNotContain "geofenceName"
    }

    @Test
    fun toEventProperties_givenGeoset_expectGeosetIdAsString() {
        val entry = PendingGeofenceDelivery("biz-7", Event.GeofenceTransition.ENTER, 50L, "user-A", transitionId = "tid-7", geosetId = "42")

        entry.toEventProperties()["geosetId"] shouldBeEqualTo "42"
    }

    @Test
    fun toEventProperties_givenNullGeoset_expectGeosetIdOmitted() {
        // A fence with no geosets emits a single event carrying no geosetId.
        val entry = PendingGeofenceDelivery("biz-8", Event.GeofenceTransition.ENTER, 50L, "user-A", transitionId = "tid-8", geosetId = null)

        entry.toEventProperties().keys shouldNotContain "geosetId"
    }

    @Test
    fun toEventProperties_givenMetadata_expectMetadataWithPrimitiveTypesPreserved() {
        val entry = PendingGeofenceDelivery(
            "biz-m",
            Event.GeofenceTransition.ENTER,
            50L,
            "user-A",
            transitionId = "tid-m",
            metadata = mapOf(
                "category" to JsonPrimitive("office"),
                "priority" to JsonPrimitive(3),
                "ratio" to JsonPrimitive(1.5),
                "vip" to JsonPrimitive(true)
            )
        )

        @Suppress("UNCHECKED_CAST")
        val metadata = entry.toEventProperties()["metadata"] as Map<String, Any>
        metadata["category"] shouldBeEqualTo "office"
        metadata["priority"] shouldBeEqualTo 3L
        metadata["ratio"] shouldBeEqualTo 1.5
        metadata["vip"] shouldBeEqualTo true
    }

    @Test
    fun toEventProperties_givenEmptyMetadata_expectEmptyMetadataObject() {
        // `metadata` is always present (empty object when the fence has none), never omitted.
        val entry = PendingGeofenceDelivery("biz-m2", Event.GeofenceTransition.ENTER, 50L, "user-A", transitionId = "tid-m2")

        @Suppress("UNCHECKED_CAST")
        val metadata = entry.toEventProperties()["metadata"] as Map<String, Any>
        metadata.isEmpty() shouldBeEqualTo true
    }

    @Test
    fun toEventProperties_givenNonPrimitiveMetadataValue_expectItDroppedButPrimitivesKept() {
        // Contract is primitives only; a stray nested object is skipped, not crashed on.
        val entry = PendingGeofenceDelivery(
            "biz-m3",
            Event.GeofenceTransition.ENTER,
            50L,
            "user-A",
            transitionId = "tid-m3",
            metadata = mapOf(
                "category" to JsonPrimitive("office"),
                "nested" to buildJsonObject { put("x", JsonPrimitive(1)) }
            )
        )

        @Suppress("UNCHECKED_CAST")
        val metadata = entry.toEventProperties()["metadata"] as Map<String, Any>
        metadata.keys shouldContain "category"
        metadata.keys shouldNotContain "nested"
    }

    @Test
    fun withFreshestEventData_givenCachedRegion_expectNameAndMetadataFromCache() {
        val entry = PendingGeofenceDelivery(
            "biz-h",
            Event.GeofenceTransition.ENTER,
            50L,
            "user-A",
            transitionId = "tid-h",
            geofenceName = "Stale",
            metadata = mapOf("k" to JsonPrimitive("stale"))
        )
        val cached = GeofenceRegion(
            id = "biz-h",
            latitude = 1.0,
            longitude = 2.0,
            radius = 100f,
            name = "Fresh",
            metadata = mapOf("k" to JsonPrimitive("fresh"))
        )

        val resolved = entry.withFreshestEventData(cached)

        resolved.geofenceName shouldBeEqualTo "Fresh"
        resolved.metadata shouldBeEqualTo mapOf("k" to JsonPrimitive("fresh"))
    }

    @Test
    fun withFreshestEventData_givenCachedRegionWithNoName_expectNameClearedFromCache() {
        // Region is still cached but its name is now gone → take the fresh (absent) name, not the
        // stale snapshot. The snapshot is a fallback only for a region that has left the cache.
        val entry = PendingGeofenceDelivery(
            "biz-h",
            Event.GeofenceTransition.ENTER,
            50L,
            "user-A",
            transitionId = "tid-h",
            geofenceName = "Snapshot Name",
            metadata = mapOf("k" to JsonPrimitive("stale"))
        )
        val cached = GeofenceRegion(
            id = "biz-h",
            latitude = 1.0,
            longitude = 2.0,
            radius = 100f,
            name = null,
            metadata = mapOf("k" to JsonPrimitive("fresh"))
        )

        val resolved = entry.withFreshestEventData(cached)

        resolved.geofenceName.shouldBeNull()
        resolved.metadata shouldBeEqualTo mapOf("k" to JsonPrimitive("fresh"))
    }

    @Test
    fun withFreshestEventData_givenNullCachedRegion_expectSnapshotRetained() {
        val entry = PendingGeofenceDelivery(
            "biz-h2",
            Event.GeofenceTransition.ENTER,
            50L,
            "user-A",
            transitionId = "tid-h2",
            geofenceName = "Snapshot",
            metadata = mapOf("k" to JsonPrimitive("snap"))
        )

        val resolved = entry.withFreshestEventData(cachedRegion = null)

        resolved shouldBeEqualTo entry
    }

    @Test
    fun serialization_givenMetadata_expectRoundTripPreservesTypes() {
        val entry = PendingGeofenceDelivery(
            "biz-ser",
            Event.GeofenceTransition.ENTER,
            9L,
            "user-A",
            transitionId = "tid-ser",
            metadata = mapOf("s" to JsonPrimitive("x"), "n" to JsonPrimitive(7), "b" to JsonPrimitive(false))
        )

        val json = Json.encodeToString(PendingGeofenceDelivery.serializer(), entry)
        val restored = Json.decodeFromString(PendingGeofenceDelivery.serializer(), json)

        restored shouldBeEqualTo entry
    }

    @Test
    fun toGeofenceTransitionEvent_givenSecondsTimestamp_expectEventTimestampInMillis() {
        // `timestamp` is unix seconds; `Event.timestamp` is a `Date` (millis).
        // The conversion lives on the data class so no caller can hand-roll
        // `Date(entry.timestamp)` and silently produce a January 1970 instant.
        val entry = PendingGeofenceDelivery("biz-t", Event.GeofenceTransition.ENTER, 1_700_000_000L, "user-A", transitionId = "tid-t")

        val event = entry.toGeofenceTransitionEvent()

        event.timestamp shouldBeEqualTo Date(1_700_000_000_000L)
        event.geofenceId shouldBeEqualTo "biz-t"
        event.transition shouldBeEqualTo Event.GeofenceTransition.ENTER
        event.userId shouldBeEqualTo "user-A"
        // transitionId travels in properties on the EventBus path too.
        event.properties["transitionId"] shouldBeEqualTo "tid-t"
    }
}

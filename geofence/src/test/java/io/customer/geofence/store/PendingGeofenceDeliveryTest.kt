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

        entry.key shouldBeEqualTo "biz-1_ENTER_tid-1_none"
    }

    @Test
    fun key_givenGeoset_expectGeosetInKey() {
        // Per-geoset rows share a transition ID; the geoset keeps their store keys distinct.
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
        restored.transitionId shouldBeEqualTo "tid-2"
    }

    @Test
    fun serialization_givenRowWrittenByAnEarlierVersion_expectItStillDecodes() {
        // No @SerialName, so the constant name is the on-disk value. Only a literal row catches a
        // rename; a round trip re-encodes with the new spelling and passes.
        val legacyRow = """{"geofenceId":"biz-w","transition":"ENTER","timestamp":1,"userId":"user-A","transitionId":"tid-w"}"""

        val restored = Json.decodeFromString(PendingGeofenceDelivery.serializer(), legacyRow)

        restored.transition shouldBeEqualTo Event.GeofenceTransition.ENTER
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
        val entry = PendingGeofenceDelivery("biz-8", Event.GeofenceTransition.ENTER, 50L, "user-A", transitionId = "tid-8", geosetId = null)

        entry.toEventProperties().keys shouldNotContain "geosetId"
    }

    @Test
    fun toEventProperties_givenDwellEvidence_expectVisitPropertiesPresent() {
        val entry = PendingGeofenceDelivery(
            "biz-dwell",
            Event.GeofenceTransition.DWELL,
            1_060L,
            "user-A",
            transitionId = "tid-dwell",
            visitId = "visit-1",
            enteredAt = 1_000L,
            dwellThresholdSeconds = 60,
            dwellDurationSeconds = 67L,
            detectionSource = "location_evidence"
        )

        entry.toEventProperties() shouldContain ("transition" to "dwell")
        entry.toEventProperties() shouldContain ("visitId" to "visit-1")
        entry.toEventProperties() shouldContain ("enteredAt" to 1_000L)
        entry.toEventProperties() shouldContain ("dwellThresholdSeconds" to 60)
        entry.toEventProperties() shouldContain ("dwellDurationSeconds" to 67L)
        entry.toEventProperties() shouldContain ("detectionSource" to "location_evidence")
    }

    @Test
    fun toEventProperties_givenNativeDwellWithoutObservedEnter_expectEvidenceFieldsAbsent() {
        val entry = PendingGeofenceDelivery(
            "biz-dwell",
            Event.GeofenceTransition.DWELL,
            1_060L,
            "user-A",
            transitionId = "visit-1",
            visitId = "visit-1",
            dwellThresholdSeconds = 60,
            detectionSource = "native"
        )

        val properties = entry.toEventProperties()
        properties shouldContain ("visitId" to "visit-1")
        properties shouldContain ("dwellThresholdSeconds" to 60)
        properties.keys shouldNotContain "enteredAt"
        properties.keys shouldNotContain "dwellDurationSeconds"
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
        val entry = PendingGeofenceDelivery("biz-m2", Event.GeofenceTransition.ENTER, 50L, "user-A", transitionId = "tid-m2")

        @Suppress("UNCHECKED_CAST")
        val metadata = entry.toEventProperties()["metadata"] as Map<String, Any>
        metadata.isEmpty() shouldBeEqualTo true
    }

    @Test
    fun toEventProperties_givenNonPrimitiveMetadataValue_expectItDroppedButPrimitivesKept() {
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
        // The snapshot is a fallback only for a region that has left the cache.
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
        // `timestamp` is unix seconds; `Event.timestamp` is a millis `Date`.
        val entry = PendingGeofenceDelivery("biz-t", Event.GeofenceTransition.ENTER, 1_700_000_000L, "user-A", transitionId = "tid-t")

        val event = entry.toGeofenceTransitionEvent()

        event.timestamp shouldBeEqualTo Date(1_700_000_000_000L)
        event.geofenceId shouldBeEqualTo "biz-t"
        event.transition shouldBeEqualTo Event.GeofenceTransition.ENTER
        event.userId shouldBeEqualTo "user-A"
        event.properties["transitionId"] shouldBeEqualTo "tid-t"
    }
}

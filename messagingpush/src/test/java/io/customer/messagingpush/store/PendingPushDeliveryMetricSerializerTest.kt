package io.customer.messagingpush.store

import io.customer.messagingpush.testutils.core.IntegrationTest
import kotlinx.serialization.json.Json
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The generic disk-backed store is covered by `PendingDeliveryStoreTest` in
 * `core`. This file pins the push-specific serialized shape: the JSON field
 * stays named `deliveryId` for on-disk backward compatibility, and the
 * computed `key` override is not part of the persisted payload.
 */
@RunWith(RobolectricTestRunner::class)
class PendingPushDeliveryMetricSerializerTest : IntegrationTest() {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun roundTrip_givenValidEntry_expectAllFieldsPreserved() {
        val original = PendingPushDeliveryMetric(
            deliveryId = "delivery-abc",
            token = "token-xyz"
        )

        val encoded = json.encodeToString(PendingPushDeliveryMetric.serializer(), original)
        val decoded = json.decodeFromString(PendingPushDeliveryMetric.serializer(), encoded)

        decoded shouldBeEqualTo original
    }

    @Test
    fun toJson_givenEntry_expectDeliveryIdFieldNameAndNoComputedKeyField() {
        // The JSON-on-disk field is the domain term (`deliveryId`). The `key`
        // override is computed, not stored, so it must NOT appear in serialized
        // output — otherwise it'd both bloat the file and risk drifting from
        // `deliveryId` if either side changes.
        val entry = PendingPushDeliveryMetric(deliveryId = "d-1", token = "t-1")

        val encoded = json.encodeToString(PendingPushDeliveryMetric.serializer(), entry)

        encoded shouldContain "\"deliveryId\":\"d-1\""
        encoded shouldContain "\"token\":\"t-1\""
        encoded shouldNotContain "\"key\""
    }

    @Test
    fun fromJson_givenLegacyEntryWithTimestampField_expectDeserializesAndIgnoresField() {
        // Older builds of the SDK wrote a `timestamp` field alongside
        // `deliveryId`/`token`. With kotlinx.serialization's
        // ignoreUnknownKeys = true (set by the store's Json config), pre-upgrade
        // entries must still load cleanly so we don't lose unflushed metrics
        // across an SDK upgrade.
        val legacy = """{"deliveryId":"legacy-d","token":"legacy-t","timestamp":1700000000000}"""

        val decoded = json.decodeFromString(PendingPushDeliveryMetric.serializer(), legacy)

        decoded shouldBeEqualTo PendingPushDeliveryMetric(deliveryId = "legacy-d", token = "legacy-t")
    }

    @Test
    fun key_propertyExposesDeliveryId() {
        val entry = PendingPushDeliveryMetric(deliveryId = "d-1", token = "t")

        entry.key shouldBeEqualTo "d-1"
    }
}

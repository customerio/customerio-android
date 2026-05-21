package io.customer.messagingpush.store

import io.customer.messagingpush.testutils.core.IntegrationTest
import kotlinx.serialization.json.Json
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
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
            token = "token-xyz",
            timestamp = 1_700_000_000_000L
        )

        val encoded = json.encodeToString(PendingPushDeliveryMetric.serializer(), original)
        val decoded = json.decodeFromString(PendingPushDeliveryMetric.serializer(), encoded)

        decoded shouldBeEqualTo original
    }

    @Test
    fun toJson_givenEntry_expectDeliveryIdFieldName() {
        // The JSON-on-disk field is the domain term, not the generic "key". Renaming
        // would break readers of any unflushed pending file written by a prior version.
        val entry = PendingPushDeliveryMetric(
            deliveryId = "d-1",
            token = "t-1",
            timestamp = 1L
        )

        val encoded = json.encodeToString(PendingPushDeliveryMetric.serializer(), entry)

        encoded shouldContain "\"deliveryId\":\"d-1\""
        encoded shouldContain "\"token\":\"t-1\""
        encoded shouldContain "\"timestamp\":1"
    }

    @Test
    fun key_propertyExposesDeliveryId() {
        val entry = PendingPushDeliveryMetric(deliveryId = "d-1", token = "t", timestamp = 1L)

        entry.key shouldBeEqualTo "d-1"
    }

    @Test
    fun timestamp_propertyExposesEntryTimestamp() {
        val entry = PendingPushDeliveryMetric(deliveryId = "d", token = "t", timestamp = 42L)

        entry.timestamp shouldBeEqualTo 42L
    }
}

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
 * stays named `deliveryId`, and the computed `key` override is not part of
 * the persisted payload.
 */
@RunWith(RobolectricTestRunner::class)
class PendingPushDeliveryMetricSerializerTest : IntegrationTest() {

    @Test
    fun roundTrip_givenValidEntry_expectAllFieldsPreserved() {
        val original = PendingPushDeliveryMetric(
            deliveryId = "delivery-abc",
            token = "token-xyz"
        )

        val encoded = Json.encodeToString(PendingPushDeliveryMetric.serializer(), original)
        val decoded = Json.decodeFromString(PendingPushDeliveryMetric.serializer(), encoded)

        decoded shouldBeEqualTo original
    }

    @Test
    fun toJson_givenEntry_expectDeliveryIdFieldNameAndNoComputedKeyField() {
        // The JSON-on-disk field is the domain term (`deliveryId`). The `key`
        // override is computed, not stored, so it must NOT appear in serialized
        // output — otherwise it'd both bloat the file and risk drifting from
        // `deliveryId` if either side changes.
        val entry = PendingPushDeliveryMetric(deliveryId = "d-1", token = "t-1")

        val encoded = Json.encodeToString(PendingPushDeliveryMetric.serializer(), entry)

        encoded shouldContain "\"deliveryId\":\"d-1\""
        encoded shouldContain "\"token\":\"t-1\""
        encoded shouldNotContain "\"key\""
    }

    @Test
    fun key_propertyExposesDeliveryId() {
        val entry = PendingPushDeliveryMetric(deliveryId = "d-1", token = "t")

        entry.key shouldBeEqualTo "d-1"
    }
}

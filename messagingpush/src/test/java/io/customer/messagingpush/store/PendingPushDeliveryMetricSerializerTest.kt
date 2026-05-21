package io.customer.messagingpush.store

import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The generic disk-backed store is covered by `PendingDeliveryStoreTest` in
 * `core`. This file only checks the push-specific [PendingPushDeliveryMetric.Serializer]
 * roundtrip and the documented "skip malformed rows" contract — both are
 * push-specific behaviors that don't belong in a generic test.
 *
 * Robolectric is required because [JSONObject] is part of the Android runtime
 * and not present in plain JVM unit tests.
 */
@RunWith(RobolectricTestRunner::class)
class PendingPushDeliveryMetricSerializerTest : IntegrationTest() {

    @Test
    fun roundTrip_givenValidEntry_expectAllFieldsPreserved() {
        val original = PendingPushDeliveryMetric(
            deliveryId = "delivery-abc",
            token = "token-xyz",
            timestamp = 1_700_000_000_000L
        )

        val json = PendingPushDeliveryMetric.Serializer.toJson(original)
        val read = PendingPushDeliveryMetric.Serializer.fromJson(json)

        read shouldBeEqualTo original
    }

    @Test
    fun fromJson_givenMissingDeliveryId_expectNull() {
        val json = JSONObject().apply {
            put("token", "t")
            put("timestamp", 1L)
        }

        PendingPushDeliveryMetric.Serializer.fromJson(json).shouldBeNull()
    }

    @Test
    fun fromJson_givenBlankDeliveryId_expectNull() {
        val json = JSONObject().apply {
            put("deliveryId", "")
            put("token", "t")
            put("timestamp", 1L)
        }

        PendingPushDeliveryMetric.Serializer.fromJson(json).shouldBeNull()
    }

    @Test
    fun fromJson_givenMissingToken_expectNull() {
        val json = JSONObject().apply {
            put("deliveryId", "d")
            put("timestamp", 1L)
        }

        PendingPushDeliveryMetric.Serializer.fromJson(json).shouldBeNull()
    }

    @Test
    fun fromJson_givenMissingTimestamp_expectNull() {
        val json = JSONObject().apply {
            put("deliveryId", "d")
            put("token", "t")
        }

        PendingPushDeliveryMetric.Serializer.fromJson(json).shouldBeNull()
    }

    @Test
    fun key_returnsDeliveryId() {
        val entry = PendingPushDeliveryMetric(deliveryId = "d-1", token = "t", timestamp = 1L)

        PendingPushDeliveryMetric.Serializer.key(entry) shouldBeEqualTo "d-1"
    }

    @Test
    fun timestamp_returnsEntryTimestamp() {
        val entry = PendingPushDeliveryMetric(deliveryId = "d", token = "t", timestamp = 42L)

        PendingPushDeliveryMetric.Serializer.timestamp(entry) shouldBeEqualTo 42L
    }
}

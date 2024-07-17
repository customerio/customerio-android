package io.customer.tracking.migration.request

import org.json.JSONObject

/**
 * Interface for the migration tasks that need to be processed by the migration processor.
 * The migration tasks are used to migrate the data from the old tracking implementation
 * to the new data pipelines.
 * Each task represents specific action so it can be processed accordingly.
 */
sealed interface MigrationTask {
    val timestamp: Long
    val identifier: String

    data class IdentifyProfile(
        override val timestamp: Long,
        override val identifier: String,
        val attributes: JSONObject
    ) : MigrationTask

    data class TrackEvent(
        override val timestamp: Long,
        override val identifier: String,
        val event: String,
        val type: String,
        val properties: JSONObject
    ) : MigrationTask

    data class TrackPushMetric(
        override val timestamp: Long,
        override val identifier: String,
        val deliveryId: String,
        val deviceToken: String,
        val event: String
    ) : MigrationTask

    data class TrackDeliveryEvent(
        override val timestamp: Long,
        override val identifier: String,
        val deliveryType: String,
        val deliveryId: String,
        val event: String,
        val metadata: JSONObject
    ) : MigrationTask

    data class RegisterDeviceToken(
        override val timestamp: Long,
        override val identifier: String,
        val token: String,
        val platform: String,
        val lastUsed: Long,
        val attributes: JSONObject
    ) : MigrationTask

    data class DeletePushToken(
        override val timestamp: Long,
        override val identifier: String,
        val token: String
    ) : MigrationTask
}

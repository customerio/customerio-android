package io.customer.tracking.migration.util

import io.customer.sdk.core.util.enumValueOfOrNull
import io.customer.tracking.migration.extensions.jsonObjectOrNull
import io.customer.tracking.migration.extensions.longOrNull
import io.customer.tracking.migration.extensions.requireAndRemoveField
import io.customer.tracking.migration.extensions.requireField
import io.customer.tracking.migration.extensions.stringOrNull
import io.customer.tracking.migration.request.MigrationTask
import io.customer.tracking.migration.type.QueueTaskType
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject

/**
 * Adapter class to convert JSON strings to objects.
 * This implementation uses core JSON library to parse JSON strings to minimize dependencies.
 */
class JsonAdapter {
    fun fromJsonOrNull(json: String): JSONObject? = runCatching {
        JSONObject(json)
    }.getOrNull()

    fun fromJsonToListOrNull(json: String): JSONArray? = runCatching {
        return JSONArray(json)
    }.getOrNull()

    /**
     * Parses the migration task from the queue task JSON object and returns the result.
     * If the task is invalid, it will return failure result with an error message.
     * If the task is valid, it will return success result with the parsed migration task
     * that can be processed by the migration processor.
     */
    fun parseMigrationTask(task: JSONObject): Result<MigrationTask> = runCatching {
        val data = task.stringOrNull("data")
        require(!data.isNullOrBlank()) { "Queue task data is null or blank for $task. Could not run task." }

        val type = task.stringOrNull("type")?.let { enumValueOfOrNull<QueueTaskType>(it) }
        requireNotNull(type) { "Queue task type is invalid for $task. Could not run task." }

        val taskJson = runCatching { JSONObject(data) }.getOrElse {
            throw RuntimeException("Queue task data is invalid for $task. Could not run task.")
        }
        val timestamp = taskJson.longOrNull("timestamp") ?: Date().time

        val result = when (type) {
            QueueTaskType.IdentifyProfile -> {
                MigrationTask.IdentifyProfile(
                    timestamp = timestamp,
                    identifier = taskJson.requireField("identifier"),
                    attributes = taskJson.jsonObjectOrNull("attributes") ?: JSONObject()
                )
            }

            QueueTaskType.TrackEvent -> {
                val eventJson = taskJson.requireField<JSONObject>("event")

                MigrationTask.TrackEvent(
                    timestamp = eventJson.longOrNull("timestamp") ?: timestamp,
                    identifier = taskJson.requireField("identifier"),
                    event = eventJson.requireAndRemoveField("name"),
                    type = eventJson.requireAndRemoveField("type"),
                    properties = eventJson
                )
            }

            QueueTaskType.RegisterDeviceToken -> {
                val deviceJson = taskJson.requireField<JSONObject>("device")

                MigrationTask.RegisterDeviceToken(
                    timestamp = timestamp,
                    identifier = taskJson.requireField("profileIdentified"),
                    token = deviceJson.requireField("id"),
                    platform = deviceJson.requireField("platform"),
                    lastUsed = deviceJson.longOrNull("last_used") ?: deviceJson.longOrNull("lastUsed") ?: timestamp,
                    attributes = deviceJson.jsonObjectOrNull("attributes") ?: JSONObject()
                )
            }

            QueueTaskType.DeletePushToken -> {
                MigrationTask.DeletePushToken(
                    timestamp = timestamp,
                    identifier = taskJson.requireField("profileIdentified"),
                    token = taskJson.requireField("deviceToken")
                )
            }

            QueueTaskType.TrackPushMetric -> {
                MigrationTask.TrackPushMetric(
                    timestamp = timestamp,
                    deliveryId = taskJson.requireField("delivery_id"),
                    deviceToken = taskJson.requireField("device_id"),
                    event = taskJson.requireField("event")
                )
            }

            QueueTaskType.TrackDeliveryEvent -> {
                val payloadJson = taskJson.requireField<JSONObject>("payload")

                MigrationTask.TrackDeliveryEvent(
                    timestamp = payloadJson.longOrNull("timestamp") ?: timestamp,
                    deliveryType = taskJson.requireField("type"),
                    deliveryId = payloadJson.requireField("delivery_id"),
                    event = payloadJson.requireField("event"),
                    metadata = payloadJson.jsonObjectOrNull("metadata") ?: JSONObject()
                )
            }
        }

        return Result.success(result)
    }
}

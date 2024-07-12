package io.customer.tracking.migration.util

import io.customer.sdk.core.util.enumValueOfOrNull
import io.customer.tracking.migration.extensions.jsonObjectOrNull
import io.customer.tracking.migration.extensions.longOrNull
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
                    identifier = taskJson.requireField("identifier") { stringOrNull(it) },
                    attributes = taskJson.jsonObjectOrNull("attributes") ?: JSONObject()
                )
            }

            QueueTaskType.TrackEvent -> {
                val eventJson = taskJson.requireField("event") { jsonObjectOrNull(it) }

                MigrationTask.TrackEvent(
                    timestamp = eventJson.longOrNull("timestamp") ?: timestamp,
                    identifier = taskJson.requireField("identifier") { stringOrNull(it) },
                    event = eventJson.requireField("name") {
                        val value = stringOrNull(it)
                        remove(it)
                        return@requireField value
                    },
                    type = eventJson.requireField("type") {
                        val value = stringOrNull(it)
                        remove(it)
                        return@requireField value
                    },
                    properties = eventJson
                )
            }

            QueueTaskType.RegisterDeviceToken -> {
                val deviceJson = taskJson.requireField("device") { jsonObjectOrNull(it) }

                MigrationTask.RegisterDeviceToken(
                    timestamp = timestamp,
                    identifier = taskJson.requireField("profileIdentified") { stringOrNull(it) },
                    token = deviceJson.requireField("id") { stringOrNull(it) },
                    platform = deviceJson.requireField("platform") { stringOrNull(it) },
                    lastUsed = deviceJson.longOrNull("last_used") ?: deviceJson.longOrNull("lastUsed") ?: timestamp,
                    attributes = deviceJson.jsonObjectOrNull("attributes") ?: JSONObject()
                )
            }

            QueueTaskType.DeletePushToken -> {
                MigrationTask.DeletePushToken(
                    timestamp = timestamp,
                    identifier = taskJson.requireField("profileIdentified") { stringOrNull(it) },
                    token = taskJson.requireField("deviceToken") { stringOrNull(it) }
                )
            }

            QueueTaskType.TrackPushMetric -> {
                MigrationTask.TrackPushMetric(
                    timestamp = timestamp,
                    deliveryId = taskJson.requireField("delivery_id") { stringOrNull(it) },
                    deviceToken = taskJson.requireField("device_id") { stringOrNull(it) },
                    event = taskJson.requireField("event") { stringOrNull(it) }
                )
            }

            QueueTaskType.TrackDeliveryEvent -> {
                val payloadJson = taskJson.requireField("payload") { jsonObjectOrNull(it) }

                MigrationTask.TrackDeliveryEvent(
                    timestamp = payloadJson.longOrNull("timestamp") ?: timestamp,
                    deliveryType = taskJson.requireField("type") { stringOrNull(it) },
                    deliveryId = payloadJson.requireField("delivery_id") { stringOrNull(it) },
                    event = payloadJson.requireField("event") { stringOrNull(it) },
                    metadata = payloadJson.jsonObjectOrNull("metadata") ?: JSONObject()
                )
            }
        }

        return Result.success(result)
    }
}

private fun <T> JSONObject?.requireField(key: String, getter: JSONObject.(String) -> T?): T {
    return requireNotNull(this?.getter(key)) { "Required key '$key' is missing or null in $this. Could not parse task." }
}

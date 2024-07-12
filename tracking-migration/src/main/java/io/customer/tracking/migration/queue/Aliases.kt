package io.customer.tracking.migration.queue

import io.customer.tracking.migration.extensions.stringOrNull
import org.json.JSONObject

/**
 * These aliases are used to make the code more readable and concise
 * and eliminate the need for data classes that are only required for migration.
 * Since these class are only used in the migration module and used once,
 * we can use JSONObjects to represent the data and use aliases to make the code more readable.
 */

internal typealias QueueInventory = List<JSONObject>
internal typealias QueueModifyResult = Boolean
internal typealias QueueRunTaskResult = Result<Unit>
internal typealias QueueTask = JSONObject
internal typealias QueueTaskMetadata = JSONObject

internal val QueueTask.type: String?
    get() = stringOrNull("type")
internal val QueueTask.data: String?
    get() = stringOrNull("data")

internal val QueueTaskMetadata.taskPersistedId: String?
    get() = stringOrNull("taskPersistedId")

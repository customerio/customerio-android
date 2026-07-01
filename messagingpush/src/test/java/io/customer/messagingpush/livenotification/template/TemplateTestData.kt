package io.customer.messagingpush.livenotification.template

import org.json.JSONObject

/**
 * Merges JSON objects into a single flattened [JSONObject], mirroring how the
 * backend now delivers all live-notification template fields at the envelope
 * top level (no `attributes` / `content_state` split). Lets per-template tests
 * keep expressing inputs as two logical groups while exercising the flattened
 * `render(data = …)` contract.
 */
internal fun flatten(vararg objects: JSONObject): JSONObject {
    val data = JSONObject()
    for (obj in objects) {
        for (key in obj.keys()) {
            data.put(key, obj.get(key))
        }
    }
    return data
}

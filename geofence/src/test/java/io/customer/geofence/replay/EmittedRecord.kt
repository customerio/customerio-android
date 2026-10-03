package io.customer.geofence.replay

import io.customer.geofence.GeofenceLogTail

/** A record parsed from a log line's machine tail: the actual side graded against [ScenarioRecord]. */
internal data class EmittedRecord(val ev: String, val io: String, val fields: Map<String, String>) {
    fun geofenceIds(): List<String> =
        fields["ids"]?.split(",")?.filter { it.isNotEmpty() } ?: listOfNotNull(fields["id"])

    companion object {
        fun parse(message: String): EmittedRecord? {
            val index = message.lastIndexOf(GeofenceLogTail.DELIMITER)
            if (index < 0) return null
            val tail = message.substring(index + GeofenceLogTail.DELIMITER.length)
            val fields = mutableMapOf<String, String>()
            for (token in tail.split(" ")) {
                val parts = token.split("=", limit = 2)
                if (parts.size != 2) return null
                fields[parts[0]] = parts[1]
            }
            val ev = fields.remove("ev") ?: return null
            val io = fields.remove("io") ?: return null
            return EmittedRecord(ev, io, fields)
        }
    }
}

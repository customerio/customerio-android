package io.customer.geofence.replay

import io.customer.geofence.store.PendingGeofenceDelivery

/** Checkpoints grade the real persisted outbox, including absence and exact counts at drive time. */
internal object ReplayDeliveryAssertions {
    const val EVENT = "delivery.queued"

    private val propertyNames = setOf(
        "dwellThresholdSeconds",
        "dwellDurationSeconds",
        "visitDurationSeconds",
        "detectionSource",
        "geosetId"
    )
    private val controls = setOf("id", "t", "count", "timestampAt", "enteredAtAt", "hasVisitId", "hasEnteredAt")

    fun compare(record: ScenarioRecord, queued: List<PendingGeofenceDelivery>?, epochSeconds: Long): String? {
        val id = requireNotNull(record.string("id")) { "delivery.queued needs id" }
        val transition = requireNotNull(record.string("t")) { "delivery.queued needs t" }.lowercase()
        require(transition in setOf("enter", "dwell", "exit")) { "unknown queued transition $transition" }
        val count = record.double("count")
        require(count != null && count >= 0 && count % 1.0 == 0.0) { "delivery.queued needs a non-negative whole count" }
        require(record.fields.keys.all { it in controls || it in propertyNames }) {
            "unknown delivery assertion fields: ${record.fields.keys - controls - propertyNames}"
        }
        listOf("hasVisitId", "hasEnteredAt").filter { it in record.fields }.forEach {
            require(record.fields[it] is Boolean) { "$it must be a boolean" }
        }
        listOf("timestampAt", "enteredAtAt").filter { it in record.fields }.forEach {
            require(record.double(it)?.isFinite() == true) { "$it must be a finite number" }
        }
        if (queued == null) return "@${record.at} $EVENT: persisted outbox is unreadable"
        val actual = queued.filter { it.geofenceId == id && it.transition.name.lowercase() == transition }
        val problems = mutableListOf<String>()
        if (actual.size.toDouble() != count) problems.add("expected $count queued, got ${actual.size}")
        actual.forEach { entry ->
            val properties = entry.toEventProperties()
            propertyNames.filter { it in record.fields }.forEach { name ->
                val expected = record.fields[name]
                val value = properties[name]
                val matches = if (expected is Number && value is Number) {
                    expected.toDouble() == value.toDouble()
                } else {
                    expected == value
                }
                if (!matches) problems.add("$name expected $expected, got $value")
                if (expected == null && properties.containsKey(name)) problems.add("$name must be omitted")
            }
            record.double("timestampAt")?.let {
                if ((entry.timestamp - epochSeconds).toDouble() != it) problems.add("timestampAt expected $it, got ${entry.timestamp - epochSeconds}")
            }
            record.double("enteredAtAt")?.let {
                if (entry.enteredAt?.minus(epochSeconds)?.toDouble() != it) problems.add("enteredAtAt expected $it, got ${entry.enteredAt?.minus(epochSeconds)}")
            }
            record.boolean("hasVisitId")?.let {
                if ((entry.visitId?.isNotBlank() == true) != it) problems.add("hasVisitId expected $it")
            }
            record.boolean("hasEnteredAt")?.let {
                if (properties.containsKey("enteredAt") != it) problems.add("hasEnteredAt expected $it")
            }
        }
        return problems.takeIf { it.isNotEmpty() }?.joinToString(
            prefix = "@${record.at} $EVENT id=$id t=$transition: ",
            separator = "; "
        )
    }
}

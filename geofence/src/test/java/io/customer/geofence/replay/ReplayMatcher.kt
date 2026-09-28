package io.customer.geofence.replay

/**
 * Grades what the replayed SDK decided against what the device decided.
 *
 * Matches by `(ev, fence id, transition, why)` and by count, not timestamp: what must hold is the
 * same decisions about the same fences, the same number of times.
 */
internal object ReplayMatcher {

    /**
     * The only records a scenario asserts: the SDK's `io=out` records, decisions that leave the SDK
     * (a region set handed to the OS, a transition handed to delivery, a reset on sign-out).
     * Decisions about inputs (dedupes, drops, synthesised enters) are graded only through
     * `transition.accepted`, so a drive is not pinned to one implementation's internals.
     */
    val assertedEvents = setOf(
        "registration.applied",
        "transition.accepted",
        "module.reset"
    )

    /**
     * A decision, stripped to the parts that identify it.
     *
     * `ok` is not part of the key, so `why` is what separates a failed `module.reset`
     * (`why=os_clear_failed`, `why=other_user_signed_in`) from a successful one.
     */
    data class Key(val ev: String, val id: String?, val transition: String?, val why: String?) {
        override fun toString(): String = buildString {
            append(ev)
            id?.let { append(" id=").append(it) }
            transition?.let { append(" t=").append(it) }
            why?.let { append(" why=").append(it) }
        }
    }

    data class Mismatch(val key: Key, val expected: Int, val actual: Int)

    data class Report(
        val matched: Int,
        val mismatches: List<Mismatch>,
        val expectedTotal: Int
    ) {
        val isMatch: Boolean get() = mismatches.isEmpty()

        fun describe(): String = buildString {
            append("$matched/$expectedTotal expectations matched")
            if (mismatches.isNotEmpty()) {
                append("\n")
                for (m in mismatches.sortedBy { it.key.toString() }) {
                    val verdict = when {
                        m.actual == 0 -> "never happened"
                        m.expected == 0 -> "not expected"
                        else -> "count differs"
                    }
                    append("  ${m.key}: expected ${m.expected}, got ${m.actual} ($verdict)\n")
                }
            }
        }
    }

    fun compare(scenario: Scenario, emitted: List<EmittedRecord>): Report {
        val expected = scenario.expectations
            .filter { it.ev in assertedEvents }
            .flatMap { record -> record.geofenceIds().ifEmpty { listOf(null) }.map { keyOf(record, it) } }
            .groupingBy { it }
            .eachCount()

        val actual = emitted
            .filter { it.ev in assertedEvents }
            .flatMap { record -> record.geofenceIds().ifEmpty { listOf(null) }.map { keyOf(record, it) } }
            .groupingBy { it }
            .eachCount()

        val mismatches = (expected.keys + actual.keys)
            .mapNotNull { key ->
                val e = expected[key] ?: 0
                val a = actual[key] ?: 0
                if (e == a) null else Mismatch(key, e, a)
            }

        val matched = expected.entries.sumOf { (key, count) -> minOf(count, actual[key] ?: 0) }
        return Report(matched, mismatches, expected.values.sum())
    }

    private fun keyOf(record: ScenarioRecord, id: String?) =
        Key(record.ev, id, record.string("t")?.lowercase(), record.string("why"))

    private fun keyOf(record: EmittedRecord, id: String?) =
        Key(record.ev, id, record.fields["t"]?.lowercase(), record.fields["why"])
}

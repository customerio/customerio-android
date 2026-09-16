package io.customer.geofence.replay

/**
 * Grades what the replayed SDK decided against what the device decided.
 *
 * Matching is by `(ev, fence id, transition)` and by count — not by timestamp. A replay runs in
 * microseconds on a virtual clock, so pinning wall-clock offsets would fail on every run for no
 * behavioural reason. What must hold is that the same decisions were reached about the same
 * fences, the same number of times.
 */
internal object ReplayMatcher {

    /**
     * The only records a scenario asserts. Frozen deliberately: it is what the SDK classifies
     * `io=out`, and widening it here would let replay grade something the SDK never promised.
     *
     * Three of them. An output is a decision that crosses back out of the SDK — a region set
     * handed to the OS, a transition handed to delivery, a registration cleared on sign-out. A
     * callback deduplicated, a transition refused or an enter synthesised are decisions about
     * *inputs*; their only visible consequence is a transition that is or is not accepted, which
     * the counts here already grade. Asserting them pinned every drive to one implementation's
     * internals.
     */
    val assertedEvents = setOf(
        "registration.applied",
        "transition.accepted",
        // "Logout clears geofences" is a locked cross-platform contract, and both platforms were
        // deliberately aligned onto this key so one scenario could grade either. Leaving it out of
        // this set meant the SDK emitted it, the transform wrote it as a `then`, and the matcher
        // then ignored it — the contract was untested on Android. iOS has no allow-list and has
        // always graded it.
        "module.reset"
    )

    /**
     * A decision, stripped to the parts that identify it.
     *
     * `why` travels when an asserted record carries one, so that two decisions differing only in
     * their stated reason are not the same claim. None of the asserted events carries it today.
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

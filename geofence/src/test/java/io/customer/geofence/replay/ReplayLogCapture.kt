package io.customer.geofence.replay

import android.location.Location
import io.customer.geofence.GeofenceCrossing
import io.customer.geofence.GeofenceCrossingTransition
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger
import java.util.concurrent.TimeUnit

/**
 * Reading back what the SDK said, and turning a recorded stimulus into the crossing the OS would
 * have handed it.
 */

/**
 * Collects the diagnostic tail the SDK emits, which is the only thing a replay grades against.
 *
 * Asserting the emitted record rather than a mock's call count is what keeps replay honest: the
 * `ev=` a parser dispatches on is the same string the device wrote during the drive.
 */
internal class ReplayLogger : Logger {
    val messages = mutableListOf<String>()
    override var logLevel: CioLogLevel = CioLogLevel.DEBUG

    override fun setLogDispatcher(dispatcher: ((CioLogLevel, String) -> Unit)?) = Unit
    override fun info(message: String, tag: String?) { messages.add(message) }
    override fun debug(message: String, tag: String?) { messages.add(message) }
    override fun error(message: String, tag: String?, throwable: Throwable?) { messages.add(message) }

    /** Every record with a machine tail, in emission order. */
    fun emitted(): List<EmittedRecord> = messages.mapNotNull(EmittedRecord::parse)

    fun clear() = messages.clear()
}

/**
 * Fails loudly when the composed graph is not actually wired to the doubles.
 *
 * `overrideDependency<T>(x)` inside a `sdk { }` / `android { }` lambda resolves `x` against the
 * *graph* first, and both `SDKComponent` and `AndroidSDKComponent` declare members named
 * `secureUserStore`, `scopeProvider`, `logger` and `eventBus`. A test field with one of those names
 * is shadowed, the graph overrides a dependency with itself, and everything still compiles and
 * runs — the replay just silently drives the real component. That cost an hour once; it is not
 * costing anyone a second one.
 *
 * Checked by identity, because a same-typed substitute is exactly what the failure produces.
 */
internal fun assertComposedWith(vararg expected: Pair<String, Pair<Any, Any>>) {
    val wrong = expected.filter { (_, pair) -> pair.first !== pair.second }
    if (wrong.isNotEmpty()) {
        throw AssertionError(
            "replay composition is not wired to its doubles: " +
                wrong.joinToString { it.first } +
                ". A test field sharing a name with a DiGraph member is shadowed inside the " +
                "override lambda — rename it (the `fake` prefix exists for this)."
        )
    }
}

/** Maps a scenario's transition token onto the SDK's own vocabulary. */
internal fun crossingTransitionOf(token: String?): GeofenceCrossingTransition = when (token?.lowercase()) {
    "enter" -> GeofenceCrossingTransition.ENTER
    "exit" -> GeofenceCrossingTransition.EXIT
    else -> GeofenceCrossingTransition.UNSUPPORTED
}

/**
 * The OS fix the callback carried, rebuilt from what the drive recorded.
 *
 * Not null: the polygon controller reads this object rather than the coordinates, and every one of
 * its entry points treats a null fix as "nothing to judge" — `onMovementTriggerExit` returns on its
 * first line, `acceptCoarseTransition` accepts unconditionally and so never dedupes, and
 * `evaluateCallbackFix` takes its fail-open branch. Replaying null therefore walks past the whole
 * decision path instead of grading it, and grades green for doing so.
 *
 * `elapsedRealtimeNanos` is dated `age` before the callback, which is where the drive says the fix
 * was taken. It has to be monotonic across a drive as well as present, because the coarse dedupe
 * compares it against the previous crossing's.
 */
private fun ScenarioRecord.triggeringFix(elapsedRealtimeMillis: Long): Location? {
    val lat = double("lat") ?: return null
    val lon = double("lon") ?: return null
    return Location("replay").also { fix ->
        fix.latitude = lat
        fix.longitude = lon
        double("acc")?.let { fix.accuracy = it.toFloat() }
        val ageMillis = ((double("age") ?: 0.0) * 1000).toLong()
        fix.elapsedRealtimeNanos = TimeUnit.MILLISECONDS.toNanos((elapsedRealtimeMillis - ageMillis).coerceAtLeast(1L))
    }
}

/** Builds the crossing an `os.callback` stimulus describes. */
internal fun ScenarioRecord.toCrossing(receivedAtSeconds: Long, elapsedRealtimeMillis: Long): GeofenceCrossing {
    val token = string("t")
    return GeofenceCrossing(
        geofenceIds = geofenceIds(),
        transition = crossingTransitionOf(token),
        transitionName = token?.uppercase() ?: "UNKNOWN",
        // The scenario records the interpreted transition, not GMS's integer. Only the unsupported
        // branch reads this, and a replayed unsupported crossing has no code to report.
        rawTransitionCode = -1,
        latitude = double("lat"),
        longitude = double("lon"),
        triggeringLocation = triggeringFix(elapsedRealtimeMillis),
        // Production stamps this where the broadcast is parsed, before any dispatch work. The
        // replay's equivalent is the virtual clock at this stimulus, which the runner has already
        // stepped to `record.at`.
        receivedAtSeconds = receivedAtSeconds
    )
}

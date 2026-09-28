package io.customer.geofence.replay

import android.location.Location
import io.customer.geofence.GeofenceCrossing
import io.customer.geofence.GeofenceCrossingTransition
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger
import java.util.concurrent.TimeUnit

/**
 * Collects the SDK's log messages. Their machine tails are what a replay grades, because `ev=` is
 * the same string the device wrote during the drive.
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
 * Fails loudly when the composed graph is not wired to the doubles.
 *
 * Inside a `sdk { }` / `android { }` lambda, `overrideDependency<T>(x)` resolves `x` against the
 * graph first, so a test field named like a graph member (`secureUserStore`, `scopeProvider`,
 * `logger`, `eventBus`) is shadowed and the replay silently drives the real component. Checked by
 * identity, because that failure produces a same-typed substitute.
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
 * Built whenever the record has a position: the polygon controller treats a null fix as "nothing to
 * judge" and skips its decision path. `elapsedRealtimeNanos` is dated `age` before the callback and
 * must be monotonic across a drive, because the coarse dedupe compares consecutive crossings' fixes.
 */
internal fun ScenarioRecord.triggeringFix(elapsedRealtimeMillis: Long): Location? {
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
        // Production stamps this when the broadcast is parsed; here it is the virtual clock at this
        // stimulus.
        receivedAtSeconds = receivedAtSeconds
    )
}

package io.customer.geofence.replay

import android.location.Location
import io.customer.geofence.GeofenceCrossing
import io.customer.geofence.GeofenceCrossingTransition
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger
import java.util.concurrent.TimeUnit

/** Collects the SDK's log messages; their machine tails are what a replay grades. */
internal class ReplayLogger : Logger {
    val messages = mutableListOf<String>()
    override var logLevel: CioLogLevel = CioLogLevel.DEBUG

    override fun setLogDispatcher(dispatcher: ((CioLogLevel, String) -> Unit)?) = Unit
    override fun info(message: String, tag: String?) { messages.add(message) }
    override fun debug(message: String, tag: String?) { messages.add(message) }
    override fun error(message: String, tag: String?, throwable: Throwable?) { messages.add(message) }

    fun emitted(): List<EmittedRecord> = messages.mapNotNull(EmittedRecord::parse)

    fun clear() = messages.clear()
}

/**
 * Inside a `sdk { }` / `android { }` lambda, `overrideDependency<T>(x)` resolves `x` against the graph
 * first, so a test field named like a graph member is shadowed and the replay silently drives the real
 * component. Checked by identity, because that substitute has the same type.
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

internal fun crossingTransitionOf(token: String?): GeofenceCrossingTransition = when (token?.lowercase()) {
    "enter" -> GeofenceCrossingTransition.ENTER
    "exit" -> GeofenceCrossingTransition.EXIT
    "dwell" -> GeofenceCrossingTransition.DWELL
    else -> GeofenceCrossingTransition.UNSUPPORTED
}

/**
 * Built whenever the record has a position: the polygon controller skips its decision on a null fix.
 * `elapsedRealtimeNanos` is dated `age` before the callback and must be monotonic across a drive,
 * because the coarse dedupe compares consecutive crossings' fixes.
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

internal fun ScenarioRecord.toCrossing(receivedAtSeconds: Long, elapsedRealtimeMillis: Long): GeofenceCrossing {
    val token = string("t")
    return GeofenceCrossing(
        geofenceIds = geofenceIds(),
        transition = crossingTransitionOf(token),
        transitionName = token?.uppercase() ?: "UNKNOWN",
        // Scenarios record the interpreted transition, not GMS's integer; only the unsupported
        // branch reads it.
        rawTransitionCode = -1,
        latitude = double("lat"),
        longitude = double("lon"),
        triggeringLocation = triggeringFix(elapsedRealtimeMillis),
        // Production stamps this when the broadcast is parsed; here, the virtual clock at this stimulus.
        receivedAtSeconds = receivedAtSeconds,
        receivedAtElapsedMs = elapsedRealtimeMillis
    )
}

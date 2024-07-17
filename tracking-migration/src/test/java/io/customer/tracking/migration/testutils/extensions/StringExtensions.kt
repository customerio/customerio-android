package io.customer.tracking.migration.testutils.extensions

import io.customer.sdk.data.request.MetricEvent
import org.amshove.kluent.shouldNotBeNull

/**
 * Converts a string to an enum value of type [T].
 * If the enum value is not found, it will throw assertion error.
 */
inline fun <reified T : Enum<T>> String.enumValue(): T = when (T::class.java) {
    MetricEvent::class.java -> MetricEvent.getEvent(this).shouldNotBeNull() as T
    else -> enumValueOf<T>(this).shouldNotBeNull()
}

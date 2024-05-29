package io.customer.sdk.events

/**
 * Metric enum class to represent the metrics that can be tracked for events
 * like push, in-app, etc.
 */
enum class Metric {
    Delivered,
    Opened,
    Converted,
    Clicked
}

fun String.getMetric(): Metric {
    return if (this.isBlank()) {
        Metric.Delivered
    } else {
        Metric.values().find { value -> value.name.equals(this, ignoreCase = true) } ?: Metric.Delivered
    }
}

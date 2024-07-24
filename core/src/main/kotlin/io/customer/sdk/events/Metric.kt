package io.customer.sdk.events

import java.util.Locale

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

/**
 * Extension function to get the serialized name of [Metric] that matches the
 * server's expectation.
 */
val Metric.serializedName: String
    get() = name.lowercase(Locale.ENGLISH)

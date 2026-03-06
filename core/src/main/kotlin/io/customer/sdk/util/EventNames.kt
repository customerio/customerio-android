package io.customer.sdk.util

/**
 * Event names to identify specific events in data pipelines so they can be
 * reflected on Journeys.
 */
object EventNames {
    const val DEVICE_UPDATE = "Device Created or Updated"
    const val DEVICE_DELETE = "Device Deleted"
    const val METRIC_DELIVERY = "Report Delivery Event"

    // Event name fired by AndroidLifecyclePlugin when app enters background
    const val APPLICATION_BACKGROUNDED = "Application Backgrounded"

    // Event name for location updates tracked by the Location module
    const val LOCATION_UPDATE = "Location Update"
}

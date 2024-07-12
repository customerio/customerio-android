package io.customer.datapipelines.util

/**
 * Event names to identify specific events in data pipelines so they can be
 * reflected on Journeys.
 */
internal object EventNames {
    const val DEVICE_UPDATE = "Device Created or Updated"
    const val DEVICE_DELETE = "Device Deleted"
    const val METRIC_DELIVERY = "Report Delivery Event"
}

package io.customer.tracking.migration.type

// All the types of tasks the Tracking module runs in the background queue
internal enum class QueueTaskType {
    IdentifyProfile,
    TrackEvent,
    RegisterDeviceToken,
    DeletePushToken,
    TrackPushMetric,
    TrackDeliveryEvent
}

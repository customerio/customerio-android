package io.customer.messagingpush.diagnostics

/**
 * Constants for push notification diagnostic event names.
 * Use these constants instead of strings to avoid typos and enable IDE autocomplete.
 */
internal object PushDiagnosticEvents {
    // Success events
    const val PUSH_DELIVERED = "push_delivered"
    const val PUSH_OPENED = "push_opened"

    // Lifecycle events (for crash debugging)
    const val PUSH_NOTIFICATION_RENDER_STARTED = "push_notification_render_started"
    const val PUSH_NOTIFICATION_RENDER_COMPLETED = "push_notification_render_completed"
    const val PUSH_DEEP_LINK_HANDLING_STARTED = "push_deep_link_handling_started"
    const val PUSH_DEEP_LINK_HANDLING_COMPLETED = "push_deep_link_handling_completed"

    // Error events
    const val PUSH_DELIVERY_ERROR = "push_delivery_error"
    const val PUSH_RECEIVE_ERROR = "push_receive_error"
    const val PUSH_CLICK_ERROR = "push_click_error"
    const val PUSH_IMAGE_LOAD_ERROR = "push_image_load_error"
}

/**
 * Error type constants for diagnostic events.
 * These go in the "error_type" field of error events.
 */
internal object PushDiagnosticErrorTypes {
    // Delivery errors
    const val EMPTY_DELIVERY_ID = "empty_delivery_id"
    const val DUPLICATE_DELIVERY_ID = "duplicate_delivery_id"

    // Receive errors
    const val EMPTY_BUNDLE = "empty_bundle"
    const val NON_CIO_PUSH = "non_cio_push"

    // Click errors
    const val MISSING_PAYLOAD = "missing_payload"
    const val EXCEPTION = "exception"

    // Image errors
    const val IMAGE_DOWNLOAD_FAILED = "image_download_failed"
}

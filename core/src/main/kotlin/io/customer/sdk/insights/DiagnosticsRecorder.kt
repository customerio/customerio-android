package io.customer.sdk.insights

/**
 * Interface for recording diagnostic events throughout the SDK.
 * Use the [Diagnostics] object singleton for easy access.
 */
interface DiagnosticsRecorder {
    var isEnabled: Boolean
    fun isEnabledForEvent(event: String): Boolean
    fun record(event: DiagnosticEvent)
    fun flush()
    fun clear()
}

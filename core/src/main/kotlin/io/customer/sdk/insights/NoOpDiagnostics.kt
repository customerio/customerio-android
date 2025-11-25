package io.customer.sdk.insights

/**
 * No-op implementation of DiagnosticsRecorder that does nothing.
 * Used as a default when diagnostics is not enabled or not initialized.
 */
internal object NoOpDiagnostics : DiagnosticsRecorder {
    override var isEnabled: Boolean
        get() = false
        set(value) {
            // No-op
        }

    override fun isEnabledForEvent(event: String): Boolean {
        return false
    }

    override fun record(event: DiagnosticEvent) {
        // No-op
    }

    override fun flush() {
        // No-op
    }

    override fun clear() {
        // No-op
    }
}

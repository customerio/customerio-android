package io.customer.sdk.insights

/**
 * Interface for uploading diagnostic events to a server.
 * Implementation is provided by the datapipelines module.
 */
interface DiagnosticsUploader {
    fun upload(events: List<DiagnosticEvent>)
}

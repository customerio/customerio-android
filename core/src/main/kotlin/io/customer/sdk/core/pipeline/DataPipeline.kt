package io.customer.sdk.core.pipeline

/**
 * Abstraction for sending track events to the data pipeline.
 *
 * Modules retrieve an implementation via `SDKComponent.getOrNull<DataPipeline>()`
 * to send events directly without going through EventBus.
 */
interface DataPipeline {
    val userId: String?
    fun track(name: String, properties: Map<String, Any?>)
}

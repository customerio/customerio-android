package io.customer.sdk

import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.events.TrackMetric
import kotlinx.serialization.SerializationStrategy

/**
 * A [DataPipelineInstance] stand-in that buffers event-shaped calls invoked
 * before [CustomerIO.initialize] has completed.
 *
 * Returned by [CustomerIO.instance] when no real [CustomerIO] instance has
 * been created yet. Once [CustomerIO.initialize] runs, the buffer is drained
 * synchronously against the real instance (see
 * [PreInitEventBuffer.transitionToReady]) and any subsequent calls on this
 * proxy execute immediately by forwarding to the real instance.
 *
 * Read-side properties (`userId`, `anonymousId`, `registeredDeviceToken`,
 * `profileAttributes`, `deviceAttributes`) return safe defaults pre-init —
 * there is no synchronous source of truth available before the SDK
 * configures its analytics layer.
 */
internal class PreInitBufferingInstance(
    private val buffer: PreInitEventBuffer = PreInitEventBuffer(),
    loggerProvider: () -> Logger? = { null }
) : DataPipelineInstance() {

    @Suppress("UNUSED_PARAMETER")
    private val _logger = loggerProvider // reserved for future use; keeps signature parallel to iOS

    /** Drain the buffer into [real] and route subsequent calls there. */
    fun bindRealInstance(real: DataPipelineInstance) {
        buffer.transitionToReady(real)
    }

    // --- Read-side properties: safe defaults pre-init ---

    @Deprecated("Use setProfileAttributes() function instead")
    override val profileAttributes: CustomAttributes = emptyMap()

    @Deprecated("Use setDeviceAttributes() function instead")
    override val deviceAttributes: CustomAttributes = emptyMap()

    override val registeredDeviceToken: String? = null
    override val anonymousId: String = ""
    override val userId: String? = null

    // --- Write-side: enqueue or forward via the buffer ---

    override fun setProfileAttributes(attributes: CustomAttributes) {
        buffer.enqueue { it.setProfileAttributes(attributes) }
    }

    override fun setDeviceAttributes(attributes: CustomAttributes) {
        buffer.enqueue { it.setDeviceAttributes(attributes) }
    }

    @Suppress("DEPRECATION")
    override fun <Traits> identifyImpl(
        userId: String,
        traits: Traits,
        serializationStrategy: SerializationStrategy<Traits>
    ) {
        buffer.enqueue { it.identify(userId, traits, serializationStrategy) }
    }

    @Suppress("DEPRECATION")
    override fun <T> trackImpl(
        name: String,
        properties: T,
        serializationStrategy: SerializationStrategy<T>
    ) {
        buffer.enqueue { it.track(name, properties, serializationStrategy) }
    }

    @Suppress("DEPRECATION")
    override fun <T> screenImpl(
        title: String,
        properties: T,
        serializationStrategy: SerializationStrategy<T>
    ) {
        buffer.enqueue { it.screen(title, properties, serializationStrategy) }
    }

    override fun clearIdentifyImpl() {
        buffer.enqueue { it.clearIdentify() }
    }

    override fun trackMetricImpl(event: TrackMetric) {
        buffer.enqueue { it.trackMetric(event) }
    }

    override fun registerDeviceTokenImpl(deviceToken: String) {
        buffer.enqueue { it.registerDeviceToken(deviceToken) }
    }

    override fun deleteDeviceTokenImpl() {
        buffer.enqueue { it.deleteDeviceToken() }
    }

    // Test-only access to the buffer for state inspection
    internal val internalBuffer: PreInitEventBuffer get() = buffer
}

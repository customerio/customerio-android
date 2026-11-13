package io.customer.datapipelines.handoff

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.ScreenEvent
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.emptyJsonObject
import com.segment.analytics.kotlin.core.platform.EnrichmentClosure
import io.customer.datapipelines.di.segmentGatewayFactory
import io.customer.sdk.core.di.SDKComponent
import java.util.WeakHashMap
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonObject

/**
 * Every call the SDK makes into Segment, and the identity reads behind its getters. Going through
 * one gateway means each hand-off is logged ([DataPipelinesLogTail]) and a test can wrap it. Plugin
 * registration, configuration and storage stay on [Analytics] itself.
 *
 * The members mirror the [Analytics] overloads the SDK calls, so a call site keeps the exact
 * overload and serializer it used before.
 */
internal interface SegmentGateway {
    fun <T> identify(userId: String, traits: T, serializationStrategy: SerializationStrategy<T>)

    /** The anonymous identify: Segment fills in the user when it processes the event. */
    fun <T> identify(traits: T, serializationStrategy: SerializationStrategy<T>)

    fun <T> track(name: String, properties: T, serializationStrategy: SerializationStrategy<T>, enrichment: EnrichmentClosure? = null)

    fun track(name: String, properties: JsonObject = emptyJsonObject)

    fun <T> screen(title: String, properties: T, serializationStrategy: SerializationStrategy<T>)

    fun screen(title: String)

    fun process(event: BaseEvent, enrichment: EnrichmentClosure? = null)

    fun reset()

    fun userId(): String?

    fun anonymousId(): String

    fun traits(): JsonObject?
}

// One gateway per Analytics instance, released with it
private val gateways = WeakHashMap<Analytics, SegmentGateway>()

/** The gateway for [this] instance, made on first use; tests can register a factory that wraps it. */
internal fun Analytics.gateway(): SegmentGateway = synchronized(gateways) {
    gateways.getOrPut(this) { SDKComponent.segmentGatewayFactory?.invoke(this) ?: LoggingSegmentGateway(AnalyticsSegmentGateway(this)) }
}

/** Logs each hand-off as SDK [instance]'s, then makes the call on [delegate] unchanged. */
internal class LoggingSegmentGateway(
    private val delegate: SegmentGateway,
    private val instance: Int? = DataPipelinesLogTail.currentInstance
) : SegmentGateway {

    override fun <T> identify(userId: String, traits: T, serializationStrategy: SerializationStrategy<T>) {
        DataPipelinesLogTail.handOff(instance, "segment.identify", userId = userId, properties = { DataPipelinesLogTail.encode(serializationStrategy, traits) })
        delegate.identify(userId = userId, traits = traits, serializationStrategy = serializationStrategy)
    }

    override fun <T> identify(traits: T, serializationStrategy: SerializationStrategy<T>) {
        DataPipelinesLogTail.handOff(instance, "segment.identify", properties = { DataPipelinesLogTail.encode(serializationStrategy, traits) })
        delegate.identify(traits = traits, serializationStrategy = serializationStrategy)
    }

    override fun <T> track(name: String, properties: T, serializationStrategy: SerializationStrategy<T>, enrichment: EnrichmentClosure?) {
        DataPipelinesLogTail.handOff(instance, "segment.track", event = name, properties = { DataPipelinesLogTail.encode(serializationStrategy, properties) }, enrichment = enrichment)
        delegate.track(name = name, properties = properties, serializationStrategy = serializationStrategy, enrichment = enrichment)
    }

    override fun track(name: String, properties: JsonObject) {
        DataPipelinesLogTail.handOff(instance, "segment.track", event = name, properties = { properties })
        delegate.track(name, properties)
    }

    override fun <T> screen(title: String, properties: T, serializationStrategy: SerializationStrategy<T>) {
        DataPipelinesLogTail.handOff(instance, "segment.screen", event = title, properties = { DataPipelinesLogTail.encode(serializationStrategy, properties) })
        delegate.screen(title = title, properties = properties, serializationStrategy = serializationStrategy)
    }

    override fun screen(title: String) {
        DataPipelinesLogTail.handOff(instance, "segment.screen", event = title)
        delegate.screen(title)
    }

    override fun process(event: BaseEvent, enrichment: EnrichmentClosure?) {
        if (DataPipelinesLogTail.isEnabled) {
            // Segment keeps a userId the event already holds, so it is carried
            val userId = runCatching { event.userId }.getOrNull()?.takeIf { it.isNotBlank() }
            when (event) {
                is TrackEvent -> DataPipelinesLogTail.handOff(instance, "segment.track", event = event.event, userId = userId, properties = { event.properties }, enrichment = enrichment)
                is ScreenEvent -> DataPipelinesLogTail.handOff(instance, "segment.screen", event = event.name, userId = userId, properties = { event.properties }, enrichment = enrichment)
                is IdentifyEvent -> DataPipelinesLogTail.handOff(instance, "segment.identify", userId = userId, properties = { event.traits }, enrichment = enrichment)
                else -> DataPipelinesLogTail.handOff(instance, "segment.${event.type.name.lowercase()}", userId = userId, enrichment = enrichment)
            }
        }
        delegate.process(event, enrichment)
    }

    override fun reset() {
        DataPipelinesLogTail.reset(instance)
        delegate.reset()
    }

    override fun userId(): String? = delegate.userId()

    override fun anonymousId(): String = delegate.anonymousId()

    override fun traits(): JsonObject? = delegate.traits()
}

/** Makes each call on [analytics] unchanged. */
internal class AnalyticsSegmentGateway(private val analytics: Analytics) : SegmentGateway {

    override fun <T> identify(userId: String, traits: T, serializationStrategy: SerializationStrategy<T>) {
        analytics.identify(userId = userId, traits = traits, serializationStrategy = serializationStrategy)
    }

    override fun <T> identify(traits: T, serializationStrategy: SerializationStrategy<T>) {
        analytics.identify(traits = traits, serializationStrategy = serializationStrategy)
    }

    override fun <T> track(name: String, properties: T, serializationStrategy: SerializationStrategy<T>, enrichment: EnrichmentClosure?) {
        analytics.track(name = name, properties = properties, serializationStrategy = serializationStrategy, enrichment = enrichment)
    }

    override fun track(name: String, properties: JsonObject) {
        analytics.track(name, properties)
    }

    override fun <T> screen(title: String, properties: T, serializationStrategy: SerializationStrategy<T>) {
        analytics.screen(title = title, properties = properties, serializationStrategy = serializationStrategy)
    }

    override fun screen(title: String) {
        analytics.screen(title)
    }

    override fun process(event: BaseEvent, enrichment: EnrichmentClosure?) {
        analytics.process(event, enrichment)
    }

    override fun reset() {
        analytics.reset()
    }

    override fun userId(): String? = analytics.userId()

    override fun anonymousId(): String = analytics.anonymousId()

    override fun traits(): JsonObject? = analytics.traits()
}

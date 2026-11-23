package io.customer.datapipelines.handoff

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.emptyJsonObject
import com.segment.analytics.kotlin.core.platform.EnrichmentClosure
import io.customer.datapipelines.di.segmentGatewayFactory
import io.customer.sdk.core.di.SDKComponent
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonObject

/**
 * Every call the SDK makes into Segment, and the identity reads behind its getters. Going through
 * one gateway means a test can wrap each hand-off. Plugin registration, configuration and storage
 * stay on [Analytics] itself.
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
    gateways.getOrPut(this) { SDKComponent.segmentGatewayFactory?.invoke(this) ?: AnalyticsSegmentGateway(this) }
}

/**
 * Makes each call on the instance unchanged.
 *
 * The instance is held weakly: [gateways] is keyed by the same instance, so a strong reference here
 * would keep every instance, including shut-down ones, alive for the life of the process. Callers
 * hold the instance themselves, so it is only gone when nothing can reach this gateway either; a
 * call made after that is dropped and the getters return empty values.
 */
internal class AnalyticsSegmentGateway(analytics: Analytics) : SegmentGateway {
    private val analyticsRef = WeakReference(analytics)

    private inline fun <R> onAnalytics(whenReleased: R, block: Analytics.() -> R): R {
        val analytics = analyticsRef.get() ?: return whenReleased
        return analytics.block()
    }

    override fun <T> identify(userId: String, traits: T, serializationStrategy: SerializationStrategy<T>) =
        onAnalytics(Unit) { identify(userId = userId, traits = traits, serializationStrategy = serializationStrategy) }

    override fun <T> identify(traits: T, serializationStrategy: SerializationStrategy<T>) =
        onAnalytics(Unit) { identify(traits = traits, serializationStrategy = serializationStrategy) }

    override fun <T> track(name: String, properties: T, serializationStrategy: SerializationStrategy<T>, enrichment: EnrichmentClosure?) =
        onAnalytics(Unit) { track(name = name, properties = properties, serializationStrategy = serializationStrategy, enrichment = enrichment) }

    override fun track(name: String, properties: JsonObject) =
        onAnalytics(Unit) { track(name, properties) }

    override fun <T> screen(title: String, properties: T, serializationStrategy: SerializationStrategy<T>) =
        onAnalytics(Unit) { screen(title = title, properties = properties, serializationStrategy = serializationStrategy) }

    override fun screen(title: String) = onAnalytics(Unit) { screen(title) }

    override fun process(event: BaseEvent, enrichment: EnrichmentClosure?) =
        onAnalytics(Unit) { process(event, enrichment) }

    override fun reset() = onAnalytics(Unit) { reset() }

    override fun userId(): String? = onAnalytics(null) { userId() }

    override fun anonymousId(): String = onAnalytics("") { anonymousId() }

    override fun traits(): JsonObject? = onAnalytics(null) { traits() }
}

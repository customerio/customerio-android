package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.platform.EventPlugin
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.utilities.putInContext
import io.customer.sdk.core.pipeline.ProfileEnrichmentRegistry
import io.customer.sdk.core.util.Logger
import kotlinx.serialization.json.JsonPrimitive

/**
 * Generic Segment enrichment plugin that queries all registered
 * [ProfileEnrichmentProvider] instances and adds their attributes
 * to identify event context.
 *
 * This plugin has zero knowledge of specific modules — providers
 * manage their own state and return primitive-valued maps.
 */
internal class ProfileEnrichmentPlugin(
    private val registry: ProfileEnrichmentRegistry,
    private val logger: Logger
) : EventPlugin {
    override val type: Plugin.Type = Plugin.Type.Enrichment
    override lateinit var analytics: Analytics

    override fun identify(payload: IdentifyEvent): BaseEvent {
        for (provider in registry.getAll()) {
            val attributes = provider.getProfileEnrichmentAttributes() ?: continue
            for ((key, value) in attributes) {
                val jsonValue = when (value) {
                    is String -> JsonPrimitive(value)
                    is Number -> JsonPrimitive(value)
                    is Boolean -> JsonPrimitive(value)
                    else -> {
                        logger.debug("Skipping non-primitive enrichment attribute: $key")
                        continue
                    }
                }
                payload.putInContext(key, jsonValue)
            }
        }
        return payload
    }
}

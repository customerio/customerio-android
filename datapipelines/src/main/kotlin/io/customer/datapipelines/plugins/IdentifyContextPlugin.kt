package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.platform.EventPlugin
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.utilities.putInContext
import io.customer.sdk.core.pipeline.IdentifyContextRegistry
import io.customer.sdk.core.util.Logger
import kotlinx.serialization.json.JsonPrimitive

/**
 * Generic Segment enrichment plugin that queries all registered
 * [IdentifyContextProvider][io.customer.sdk.core.pipeline.IdentifyContextProvider]
 * instances and adds their entries to the identify event context.
 *
 * This plugin has zero knowledge of specific modules — providers
 * manage their own state and return primitive-valued maps.
 */
internal class IdentifyContextPlugin(
    private val registry: IdentifyContextRegistry,
    private val logger: Logger
) : EventPlugin {
    override val type: Plugin.Type = Plugin.Type.Enrichment
    override lateinit var analytics: Analytics

    override fun identify(payload: IdentifyEvent): BaseEvent {
        for (provider in registry.getAll()) {
            try {
                val context = provider.getIdentifyContext()
                if (context.isEmpty()) continue
                for ((key, value) in context) {
                    val jsonValue = when (value) {
                        is String -> JsonPrimitive(value)
                        is Number -> JsonPrimitive(value)
                        is Boolean -> JsonPrimitive(value)
                        else -> {
                            logger.debug("Skipping non-primitive context entry: $key")
                            continue
                        }
                    }
                    payload.putInContext(key, jsonValue)
                }
            } catch (e: Exception) {
                logger.error("IdentifyContextProvider failed: ${e.message}")
            }
        }
        return payload
    }
}

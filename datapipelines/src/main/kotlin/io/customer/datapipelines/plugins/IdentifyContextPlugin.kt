package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.platform.EventPlugin
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.utilities.putInContext
import io.customer.sdk.core.pipeline.IdentifyHookRegistry
import io.customer.sdk.core.util.Logger
import kotlinx.serialization.json.JsonPrimitive

/**
 * Segment enrichment plugin that delegates to registered [IdentifyHook]
 * instances for both identify enrichment and reset lifecycle.
 *
 * On identify: queries all hooks for context entries and adds them
 * to the event context via `putInContext()`.
 *
 * On reset: propagates synchronously to all hooks so they clear
 * cached state before a subsequent identify() picks up stale values.
 *
 * This plugin has zero knowledge of specific modules — hooks
 * manage their own state and return primitive-valued maps.
 */
internal class IdentifyContextPlugin(
    private val registry: IdentifyHookRegistry,
    private val logger: Logger
) : EventPlugin {
    override val type: Plugin.Type = Plugin.Type.Enrichment
    override lateinit var analytics: Analytics

    /**
     * Called synchronously by analytics.reset() during clearIdentify().
     * Propagates to all hooks so they clear cached data before a subsequent
     * identify() can pick up stale values.
     */
    override fun reset() {
        super.reset()
        for (hook in registry.getAll()) {
            try {
                hook.resetContext()
            } catch (e: Exception) {
                logger.error("IdentifyHook reset failed: ${e.message}")
            }
        }
    }

    override fun identify(payload: IdentifyEvent): BaseEvent {
        for (hook in registry.getAll()) {
            try {
                val context = hook.getIdentifyContext()
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
                logger.error("IdentifyHook failed: ${e.message}")
            }
        }
        return payload
    }
}

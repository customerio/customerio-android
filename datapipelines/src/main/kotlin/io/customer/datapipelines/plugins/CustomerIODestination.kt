package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.AliasEvent
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.Constants
import com.segment.analytics.kotlin.core.GroupEvent
import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.ScreenEvent
import com.segment.analytics.kotlin.core.Settings
import com.segment.analytics.kotlin.core.System
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.platform.DestinationPlugin
import com.segment.analytics.kotlin.core.platform.EventPipeline
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.platform.VersionedPlugin
import com.segment.analytics.kotlin.core.platform.plugins.DestinationMetadataPlugin
import com.segment.analytics.kotlin.core.platform.policies.CountBasedFlushPolicy
import com.segment.analytics.kotlin.core.platform.policies.FlushPolicy
import com.segment.analytics.kotlin.core.platform.policies.FrequencyFlushPolicy
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import sovran.kotlin.Subscriber

/**
 * CustomerIOSettings is a data class that holds the settings for the CustomerIODestination plugin.
 * @param apiKey: The Customer IO API key
 * @param apiHost: The Customer IO API host
 */
@Serializable
data class CustomerIOSettings(
    var apiKey: String,
    var apiHost: String? = null
)

/**
 * CustomerIODestination plugin that is used to send events to Customer IO CDP api, in the choice of region.
 * How it works
 * - Plugin receives `apiHost` settings
 * - We store events into a file with the batch api format (@link {https://customer.io/docs/api/cdp/#operation/batch})
 * - We upload events on a dedicated thread using the batch api
 */
class CustomerIODestination : DestinationPlugin(), VersionedPlugin, Subscriber {

    private var pipeline: EventPipeline? = null
    private var flushPolicies: List<FlushPolicy> = emptyList()
    override val key: String = "Customer.io Data Pipelines"

    override fun track(payload: TrackEvent): BaseEvent {
        enqueue(payload)
        return payload
    }

    override fun identify(payload: IdentifyEvent): BaseEvent {
        enqueue(payload)
        return payload
    }

    override fun screen(payload: ScreenEvent): BaseEvent {
        enqueue(payload)
        return payload
    }

    override fun group(payload: GroupEvent): BaseEvent {
        enqueue(payload)
        return payload
    }

    override fun alias(payload: AliasEvent): BaseEvent {
        enqueue(payload)
        return payload
    }

    private fun enqueue(payload: BaseEvent) {
        pipeline?.put(payload)
    }

    override fun setup(analytics: Analytics) {
        super.setup(analytics)

        // convert flushAt and flushIntervals into FlushPolicies
        flushPolicies = analytics.configuration.flushPolicies.ifEmpty {
            listOf(
                CountBasedFlushPolicy(analytics.configuration.flushAt),
                FrequencyFlushPolicy(analytics.configuration.flushInterval * 1000L)
            )
        }

        // Add DestinationMetadata enrichment plugin
        add(DestinationMetadataPlugin())

        with(analytics) {
            pipeline = EventPipeline(
                analytics,
                key,
                configuration.writeKey,
                flushPolicies,
                configuration.apiHost
            )

            analyticsScope.launch(analyticsDispatcher) {
                store.subscribe(
                    subscriber = this@CustomerIODestination,
                    stateClazz = System::class,
                    initialState = true,
                    handler = this@CustomerIODestination::onEnableToggled
                )
            }
        }
    }

    override fun update(settings: Settings, type: Plugin.UpdateType) {
        super.update(settings, type)
        if (settings.hasIntegrationSettings(this)) {
            // only populate the apiHost value if it exists
            settings.destinationSettings<CustomerIOSettings>(key)?.apiHost?.let {
                pipeline?.apiHost = it
            }
        }
    }

    override fun flush() {
        pipeline?.flush()
    }

    override fun version(): String {
        return Constants.LIBRARY_VERSION
    }

    internal fun onEnableToggled(state: System) {
        if (state.enabled) {
            pipeline?.start()
        } else {
            pipeline?.stop()
        }
    }
}

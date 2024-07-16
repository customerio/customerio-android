package io.customer.datapipelines.migration

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.ScreenEvent
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.emptyJsonObject
import com.segment.analytics.kotlin.core.utilities.putAll
import io.customer.datapipelines.extensions.toJsonObject
import io.customer.datapipelines.util.EventNames
import io.customer.sdk.CustomerIO
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import io.customer.tracking.migration.MigrationAssistant
import io.customer.tracking.migration.MigrationProcessor
import io.customer.tracking.migration.request.MigrationTask
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Class responsible for migrating the existing tracking data to the new data-pipelines implementation.
 * It implements the [MigrationProcessor] interface to process old existing tasks and migrate them to newer implementation.
 */
internal class TrackingMigrationProcessor(
    private val dataPipelineInstance: CustomerIO,
    migrationSiteId: String
) : MigrationProcessor {
    private val logger: Logger = SDKComponent.logger
    private val analytics: Analytics = dataPipelineInstance.analytics
    private val trackingMigrationPlugin = TrackingMigrationPlugin()

    // Start the migration process in init block to start migration as soon as possible
    // and to avoid any manual calls to replay migration.
    init {
        runCatching {
            analytics.add(trackingMigrationPlugin)
            // Start the migration process by initializing MigrationAssistant
            MigrationAssistant.start(
                migrationProcessor = this,
                migrationSiteId = migrationSiteId
            )
        }.onFailure { ex ->
            logger.error("Migration failed with exception: $ex")
        }
    }

    override fun onMigrationCompleted() {
        // Remove tracking migration plugin after migration is completed to
        // avoid any further processing unnecessarily
        // Remove the plugin in same dispatcher as analytics uses to process the events
        // so that it is removed after all the pending migration events are processed.
        analytics.analyticsScope.launch(analytics.analyticsDispatcher) {
            analytics.remove(trackingMigrationPlugin)
        }
    }

    override fun processProfileMigration(identifier: String): Result<Unit> = runCatching {
        dataPipelineInstance.identify(userId = identifier)
    }

    override suspend fun processTask(task: MigrationTask): Result<Unit> = runCatching {
        val trackEvent = when (task) {
            is MigrationTask.IdentifyProfile -> IdentifyEvent(
                userId = task.identifier,
                traits = task.attributes.toJsonObject()
            )

            is MigrationTask.TrackEvent -> if (task.type == "screen") {
                ScreenEvent(
                    name = task.event,
                    category = "",
                    properties = task.properties.toJsonObject()
                )
            } else {
                TrackEvent(
                    event = task.event,
                    properties = task.properties.toJsonObject()
                )
            }

            is MigrationTask.TrackPushMetric -> TrackEvent(
                event = EventNames.METRIC_DELIVERY,
                properties = buildJsonObject {
                    put("recipient", task.deviceToken)
                    put("deliveryId", task.deliveryId)
                    put("metric", task.event)
                }
            )

            is MigrationTask.TrackDeliveryEvent -> TrackEvent(
                event = EventNames.METRIC_DELIVERY,
                properties = buildJsonObject {
                    putAll(task.metadata.toJsonObject())
                    put("deliveryId", task.deliveryId)
                    put("metric", task.event)
                }
            )

            is MigrationTask.RegisterDeviceToken -> TrackEvent(
                event = EventNames.DEVICE_UPDATE,
                properties = buildJsonObject {
                    put("token", task.token)
                    put("properties", task.attributes.toJsonObject())
                }
            ).addDeviceContextExtras(
                mapOf("token" to task.token, "type" to "android")
            )

            is MigrationTask.DeletePushToken -> TrackEvent(
                event = EventNames.DEVICE_DELETE,
                properties = emptyJsonObject
            ).addDeviceContextExtras(
                mapOf("token" to task.token, "type" to "android")
            )
        }

        trackEvent.timestamp = task.timestamp.toString()
        task.identifier?.let { trackEvent.userId = it }

        logger.debug("processing migrated task: $trackEvent")
        analytics.process(trackEvent)
    }

    private fun <E : BaseEvent> E.addDeviceContextExtras(extras: Map<String, String>): E = this.apply {
        trackingMigrationPlugin.addDeviceContextExtras(this, extras)
    }
}

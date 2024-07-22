package io.customer.datapipelines.migration

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.ScreenEvent
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.emptyJsonObject
import com.segment.analytics.kotlin.core.platform.EnrichmentClosure
import com.segment.analytics.kotlin.core.utilities.putAll
import com.segment.analytics.kotlin.core.utilities.putInContextUnderKey
import io.customer.datapipelines.extensions.toJsonObject
import io.customer.datapipelines.util.EventNames
import io.customer.sdk.CustomerIO
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import io.customer.tracking.migration.MigrationAssistant
import io.customer.tracking.migration.MigrationProcessor
import io.customer.tracking.migration.request.MigrationTask
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
    private val globalPreferenceStore = SDKComponent.android().globalPreferenceStore
    private val analytics: Analytics = dataPipelineInstance.analytics

    // Start the migration process in init block to start migration as soon as possible
    // and to avoid any manual calls to replay migration.
    init {
        runCatching {
            // Start the migration process by initializing MigrationAssistant
            MigrationAssistant.start(
                migrationProcessor = this,
                migrationSiteId = migrationSiteId
            )
        }.onFailure { ex ->
            logger.error("Migration failed with exception: $ex")
        }
    }

    override fun processProfileMigration(identifier: String): Result<Unit> = runCatching {
        dataPipelineInstance.identify(userId = identifier)
    }

    override fun processDeviceMigration(oldDeviceToken: String) = runCatching {
        logger.debug("Migrating existing device with token: $oldDeviceToken")
        globalPreferenceStore.saveDeviceToken(oldDeviceToken)
    }

    private data class MigrationEventData(
        val trackEvent: BaseEvent,
        val enrichmentClosure: EnrichmentClosure? = null
    )
    override suspend fun processTask(task: MigrationTask): Result<Unit> = runCatching {
        val eventData = when (task) {
            is MigrationTask.IdentifyProfile -> MigrationEventData(
                trackEvent = IdentifyEvent(
                    userId = task.identifier,
                    traits = task.attributes.toJsonObject()
                )
            )

            is MigrationTask.TrackEvent -> MigrationEventData(
                trackEvent = if (task.type == "screen") {
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
            )

            is MigrationTask.TrackPushMetric -> MigrationEventData(
                trackEvent = TrackEvent(
                    event = EventNames.METRIC_DELIVERY,
                    properties = buildJsonObject {
                        put("recipient", task.deviceToken)
                        put("deliveryId", task.deliveryId)
                        put("metric", task.event)
                    }
                )
            )

            is MigrationTask.TrackDeliveryEvent -> MigrationEventData(
                trackEvent = TrackEvent(
                    event = EventNames.METRIC_DELIVERY,
                    properties = buildJsonObject {
                        putAll(task.metadata.toJsonObject())
                        put("deliveryId", task.deliveryId)
                        put("metric", task.event)
                    }
                )
            )

            is MigrationTask.RegisterDeviceToken -> MigrationEventData(
                trackEvent = TrackEvent(
                    event = EventNames.DEVICE_UPDATE,
                    properties = buildJsonObject {
                        put("token", task.token)
                        put("properties", task.attributes.toJsonObject())
                    }
                ),
                enrichmentClosure = { event ->
                    event?.putInContextUnderKey("device", "token", task.token)
                    event?.putInContextUnderKey("device", "type", "android")
                }
            )

            is MigrationTask.DeletePushToken -> MigrationEventData(
                trackEvent = TrackEvent(
                    event = EventNames.DEVICE_DELETE,
                    properties = emptyJsonObject
                ),
                enrichmentClosure = { event ->
                    event?.putInContextUnderKey("device", "token", task.token)
                    event?.putInContextUnderKey("device", "type", "android")
                }
            )
        }

        eventData.trackEvent.timestamp = task.timestamp.toString()
        eventData.trackEvent.userId = task.identifier

        logger.debug("processing migrated task: $eventData")
        analytics.process(eventData.trackEvent, eventData.enrichmentClosure)
    }
}

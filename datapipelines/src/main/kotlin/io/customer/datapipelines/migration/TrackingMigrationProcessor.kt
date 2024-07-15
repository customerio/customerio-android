package io.customer.datapipelines.migration

import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.ScreenEvent
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.emptyJsonObject
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

    // Start the migration process in init block to start migration as soon as possible
    // and to avoid any manual calls to replay migration.
    init {
        runCatching {
            // Start the migration process by initializing MigrationAssistant
            MigrationAssistant.start(
                migrationProcessor = this,
                migrationSiteId = migrationSiteId
            )
        }.fold(
            onSuccess = {
                logger.debug("Migration completed successfully")
            },
            onFailure = { ex ->
                logger.error("Migration failed with exception: $ex")
            }
        )
    }

    override fun processProfileMigration(identifier: String): Result<Unit> {
        dataPipelineInstance.identify(userId = identifier)
        return Result.success(Unit)
    }

    override suspend fun processTask(task: MigrationTask): Result<Unit> {
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
            ).apply {
                putInContextUnderKey("device", "token", task.token)
                putInContextUnderKey("device", "type", "android")
            }

            is MigrationTask.DeletePushToken -> TrackEvent(
                event = EventNames.DEVICE_DELETE,
                properties = emptyJsonObject
            ).apply {
                putInContextUnderKey("device", "token", task.token)
                putInContextUnderKey("device", "type", "android")
            }
        }

        trackEvent.timestamp = task.timestamp.toString()
        task.identifier?.let { trackEvent.userId = it }

        logger.debug("processing migrated task: $trackEvent")
        dataPipelineInstance.analytics.process(trackEvent)

        return Result.success(Unit)
    }
}

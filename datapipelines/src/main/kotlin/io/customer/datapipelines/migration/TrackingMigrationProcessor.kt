package io.customer.datapipelines.migration

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.ScreenEvent
import com.segment.analytics.kotlin.core.System
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.platform.EnrichmentClosure
import com.segment.analytics.kotlin.core.utilities.putAll
import com.segment.analytics.kotlin.core.utilities.putInContextUnderKey
import io.customer.datapipelines.extensions.toJsonObject
import io.customer.datapipelines.util.SegmentInstantFormatter
import io.customer.sdk.CustomerIO
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import io.customer.sdk.util.EventNames
import io.customer.tracking.migration.MigrationAssistant
import io.customer.tracking.migration.MigrationProcessor
import io.customer.tracking.migration.request.MigrationTask
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import sovran.kotlin.Subscriber
import sovran.kotlin.SubscriptionID

/**
 * Class responsible for migrating the existing tracking data to the new data-pipelines implementation.
 * It implements the [MigrationProcessor] interface to process old existing tasks and migrate them to newer implementation.
 */
internal class TrackingMigrationProcessor(
    private val analytics: Analytics,
    private val migrationSiteId: String
) : MigrationProcessor, Subscriber {

    companion object {
        private const val PAYLOAD_JSON_KEY_DEVICE = "device"
        private const val PAYLOAD_JSON_KEY_TOKEN = "token"
        private const val PAYLOAD_JSON_KEY_TYPE = "type"
        private const val PAYLOAD_JSON_VALUE_ANDROID = "android"
    }

    private val logger: Logger = SDKComponent.logger
    private val globalPreferenceStore = SDKComponent.android().globalPreferenceStore
    private val deviceTokenManager = SDKComponent.android().deviceTokenManager
    private var subscriptionID: SubscriptionID? = null

    // Start the migration process in init block to start migration as soon as possible
    // and to avoid any manual calls to replay migration.
    init {
        with(analytics) {
            analyticsScope.launch {
                withContext(analyticsDispatcher) {
                    analytics.store.subscribe(
                        subscriber = this@TrackingMigrationProcessor,
                        stateClazz = System::class,
                        initialState = true,
                        handler = this@TrackingMigrationProcessor::start
                    )
                }
            }
        }
    }

    private suspend fun start(state: System) {
        if (!state.running) return

        synchronized(this) {
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

        subscriptionID?.let { id ->
            analytics.store.unsubscribe(id)
            subscriptionID = null
        }
    }

    override fun processProfileMigration(identifier: String): Result<Unit> = runCatching {
        val currentUserId = analytics.userId()
        if (currentUserId != null) {
            logger.error("User already identified with userId: $currentUserId, skipping migration profile for: $identifier")
            return@runCatching
        }

        CustomerIO.instance().identify(userId = identifier)
    }

    override fun processDeviceMigration(oldDeviceToken: String): Result<Unit> = runCatching {
        when (deviceTokenManager.deviceToken) {
            null -> {
                logger.debug("Migrating existing device with token: $oldDeviceToken")
                CustomerIO.instance().registerDeviceToken(oldDeviceToken)
            }

            oldDeviceToken -> {
                logger.debug("Device token already migrated: $oldDeviceToken")
            }

            else -> {
                logger.debug("Device token refreshed, deleting old device token from migration: $oldDeviceToken")
                val deleteDeviceEvent = TrackEvent(
                    event = EventNames.DEVICE_DELETE,
                    properties = buildJsonObject {
                        put(
                            PAYLOAD_JSON_KEY_DEVICE,
                            buildJsonObject {
                                put(PAYLOAD_JSON_KEY_TOKEN, oldDeviceToken)
                                put(PAYLOAD_JSON_KEY_TYPE, PAYLOAD_JSON_VALUE_ANDROID)
                            }
                        )
                    }
                )
                analytics.process(deleteDeviceEvent) { event ->
                    event?.putInContextUnderKey(
                        PAYLOAD_JSON_KEY_DEVICE,
                        PAYLOAD_JSON_KEY_TOKEN,
                        oldDeviceToken
                    )
                    event?.putInContextUnderKey(
                        PAYLOAD_JSON_KEY_DEVICE,
                        PAYLOAD_JSON_KEY_TYPE,
                        PAYLOAD_JSON_VALUE_ANDROID
                    )
                }
            }
        }
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
                        putAll(task.attributes.toJsonObject())
                        put(
                            PAYLOAD_JSON_KEY_DEVICE,
                            buildJsonObject {
                                put(PAYLOAD_JSON_KEY_TOKEN, task.token)
                                put(PAYLOAD_JSON_KEY_TYPE, PAYLOAD_JSON_VALUE_ANDROID)
                            }
                        )
                    }
                ),
                enrichmentClosure = { event ->
                    event?.putInContextUnderKey(
                        PAYLOAD_JSON_KEY_DEVICE,
                        PAYLOAD_JSON_KEY_TOKEN,
                        task.token
                    )
                    event?.putInContextUnderKey(
                        PAYLOAD_JSON_KEY_DEVICE,
                        PAYLOAD_JSON_KEY_TYPE,
                        PAYLOAD_JSON_VALUE_ANDROID
                    )
                }
            )

            is MigrationTask.DeletePushToken -> MigrationEventData(
                trackEvent = TrackEvent(
                    event = EventNames.DEVICE_DELETE,
                    properties = buildJsonObject {
                        put(
                            PAYLOAD_JSON_KEY_DEVICE,
                            buildJsonObject {
                                put(PAYLOAD_JSON_KEY_TOKEN, task.token)
                                put(PAYLOAD_JSON_KEY_TYPE, PAYLOAD_JSON_VALUE_ANDROID)
                            }
                        )
                    }
                ),
                enrichmentClosure = { event ->
                    event?.putInContextUnderKey(
                        PAYLOAD_JSON_KEY_DEVICE,
                        PAYLOAD_JSON_KEY_TOKEN,
                        task.token
                    )
                    event?.putInContextUnderKey(
                        PAYLOAD_JSON_KEY_DEVICE,
                        PAYLOAD_JSON_KEY_TYPE,
                        PAYLOAD_JSON_VALUE_ANDROID
                    )
                }
            )
        }

        logger.debug("processing migrated task: $eventData")

        val enrichmentClosure: (event: BaseEvent) -> Unit = { event ->
            eventData.enrichmentClosure?.invoke(event)
            // Update event with task identifier and timestamp at the end to
            // make sure these properties are not overridden by any other plugin.
            event.userId = task.identifier
            SegmentInstantFormatter.from(task.timestamp)?.let { timestamp ->
                event.timestamp = timestamp
            }
            logger.debug("forwarding migrated event: $event")
        }
        analytics.process(eventData.trackEvent) { event ->
            event?.apply(enrichmentClosure)
        }
    }
}

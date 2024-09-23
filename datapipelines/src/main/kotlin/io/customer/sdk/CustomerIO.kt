package io.customer.sdk

import androidx.annotation.VisibleForTesting
import com.segment.analytics.kotlin.android.Analytics
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.ErrorHandler
import com.segment.analytics.kotlin.core.platform.EnrichmentClosure
import com.segment.analytics.kotlin.core.platform.plugins.logger.LogKind
import com.segment.analytics.kotlin.core.platform.plugins.logger.LogMessage
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.datapipelines.di.analyticsFactory
import io.customer.datapipelines.extensions.asMap
import io.customer.datapipelines.extensions.type
import io.customer.datapipelines.extensions.updateAnalyticsConfig
import io.customer.datapipelines.migration.TrackingMigrationProcessor
import io.customer.datapipelines.plugins.AutoTrackDeviceAttributesPlugin
import io.customer.datapipelines.plugins.AutomaticActivityScreenTrackingPlugin
import io.customer.datapipelines.plugins.ContextPlugin
import io.customer.datapipelines.plugins.CustomerIODestination
import io.customer.datapipelines.plugins.DataPipelinePublishedEvents
import io.customer.datapipelines.util.EventNames
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.subscribe
import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.events.TrackMetric
import io.customer.tracking.migration.MigrationProcessor
import kotlinx.serialization.SerializationStrategy

/**
 * Welcome to the Customer.io Android SDK!
 * This class is where you begin to use the SDK.
 * You must have an instance of `CustomerIO` to use the features of the SDK.
 * Create your own instance using
 * ```
 * with(CustomerIOBuilder(appContext: Application context, cdpApiKey = "XXX")) {
 *   setLogLevel(...)
 *   addCustomerIOModule(...)
 *   build()
 * }
 * ```
 * It is recommended to initialize the client in the `Application::onCreate()` method.
 * After the instance is created you can access it via singleton instance: `CustomerIO.instance()` anywhere,
 */
class CustomerIO private constructor(
    androidSDKComponent: AndroidSDKComponent,
    override val moduleConfig: DataPipelinesModuleConfig,
    overrideAnalytics: Analytics? = null
) : CustomerIOModule<DataPipelinesModuleConfig>, DataPipelineInstance() {
    override val moduleName: String = MODULE_NAME

    private val logger: Logger = SDKComponent.logger
    private val globalPreferenceStore = androidSDKComponent.globalPreferenceStore
    private val deviceStore = androidSDKComponent.deviceStore
    private val eventBus = SDKComponent.eventBus
    internal var migrationProcessor: MigrationProcessor? = null

    // Display logs under the CIO tag for easier filtering in logcat
    private val errorLogger = object : ErrorHandler {
        // Use new logger reference to avoid memory leaks
        private val logger: Logger = SDKComponent.logger

        override fun invoke(error: Throwable) {
            logger.error(error.message ?: error.stackTraceToString())
        }
    }

    // Logger implementation for Segment logger to display logs under CIO tag for easier filtering in logcat
    private val segmentLogger = object : com.segment.analytics.kotlin.core.platform.plugins.logger.Logger {
        // Use new logger reference to avoid memory leaks
        private val logger: Logger = SDKComponent.logger

        override fun parseLog(log: LogMessage) {
            val message = log.message
            when (log.kind) {
                LogKind.ERROR -> logger.error(message)
                LogKind.WARNING -> logger.info(message)
                LogKind.DEBUG -> logger.debug(message)
            }
        }
    }

    internal val analytics: Analytics = overrideAnalytics ?: Analytics(
        writeKey = moduleConfig.cdpApiKey,
        context = androidSDKComponent.applicationContext,
        configs = updateAnalyticsConfig(
            moduleConfig = moduleConfig,
            errorHandler = errorLogger
        )
    )

    private val contextPlugin: ContextPlugin = ContextPlugin(deviceStore)

    init {
        // Set analytics logger and debug logs based on SDK logger configuration
        Analytics.debugLogsEnabled = logger.logLevel == CioLogLevel.DEBUG
        Analytics.setLogger(segmentLogger)

        // Add required plugins to analytics instance
        analytics.add(contextPlugin)

        if (moduleConfig.autoAddCustomerIODestination) {
            analytics.add(CustomerIODestination())
        }

        if (moduleConfig.autoTrackActivityScreens) {
            analytics.add(AutomaticActivityScreenTrackingPlugin())
        }
        // Add auto track device attributes plugin only if enabled in config
        if (moduleConfig.autoTrackDeviceAttributes) {
            analytics.add(AutoTrackDeviceAttributesPlugin())
        }
        // Add plugin to publish events to EventBus for other modules to consume
        analytics.add(DataPipelinePublishedEvents())

        // subscribe to journey events emitted from push/in-app module to send them via data pipelines
        subscribeToJourneyEvents()
        // if profile is already identified, republish identifier for late-added modules.
        postProfileAlreadyIdentified()
    }

    private fun postProfileAlreadyIdentified() {
        analytics.userId()?.let { userId ->
            eventBus.publish(Event.ProfileIdentifiedEvent(identifier = userId))
        }
    }

    private fun subscribeToJourneyEvents() {
        eventBus.subscribe<Event.TrackPushMetricEvent> {
            trackMetric(TrackMetric.Push(metric = it.event, deliveryId = it.deliveryId, deviceToken = it.deviceToken))
        }
        eventBus.subscribe<Event.TrackInAppMetricEvent> {
            trackMetric(TrackMetric.InApp(metric = it.event, deliveryId = it.deliveryID, metadata = it.params))
        }
        eventBus.subscribe<Event.RegisterDeviceTokenEvent> {
            registerDeviceToken(deviceToken = it.token)
        }
    }

    private fun migrateTrackingEvents() {
        val migrationSiteId = moduleConfig.migrationSiteId
        // If migration site id is not provided, skip migration as it is only
        // required when upgrading from previous version with tracking implementation
        if (migrationSiteId.isNullOrBlank()) return

        logger.info("Migration site id found, migrating data from previous version.")
        // Initialize migration processor to perform migration
        migrationProcessor = TrackingMigrationProcessor(
            analytics = analytics,
            migrationSiteId = migrationSiteId
        )
    }

    override fun initialize() {
        logger.debug("CustomerIO SDK initialized with DataPipelines module.")
        // Migrate unsent events from previous version
        migrateTrackingEvents()
    }

    override var profileAttributes: CustomAttributes
        get() = analytics.traits() ?: emptyMap()
        set(value) {
            val identifier = this.userId
            if (identifier != null) {
                identify(userId = identifier, traits = value)
            } else {
                logger.debug("No user profile found, updating traits for anonymous user ${analytics.anonymousId()}")
                analytics.identify(traits = value)
            }
        }

    /**
     * Common method to identify a user profile with traits.
     * The method is responsible for identifying the user profile with the given traits
     * and running any necessary hooks.
     * All other identify methods should call this method to ensure consistency.
     */
    override fun <Traits> identify(
        userId: String,
        traits: Traits,
        serializationStrategy: SerializationStrategy<Traits>,
        enrichment: EnrichmentClosure?
    ) {
        if (userId.isBlank()) {
            logger.debug("Profile cannot be identified: Identifier is blank. Please retry with a valid, non-empty identifier.")
            return
        }

        // this is the current userId that is identified in the SDK
        val currentlyIdentifiedProfile = this.userId
        val isChangingIdentifiedProfile = currentlyIdentifiedProfile != null && currentlyIdentifiedProfile != userId
        val isFirstTimeIdentifying = currentlyIdentifiedProfile == null

        if (isChangingIdentifiedProfile) {
            logger.info("changing profile from id $currentlyIdentifiedProfile to $userId")
            if (registeredDeviceToken != null) {
                logger.debug("deleting device token before identifying new profile")
                deleteDeviceToken()
            }
        }

        logger.info("identify profile with identifier $userId and traits $traits")
        analytics.identify(
            userId = userId,
            traits = traits,
            serializationStrategy = serializationStrategy,
            enrichment = enrichment
        )

        if (isFirstTimeIdentifying || isChangingIdentifiedProfile) {
            logger.debug("first time identified or changing identified profile")
            val existingDeviceToken = registeredDeviceToken
            if (existingDeviceToken != null) {
                logger.debug("automatically registering device token to newly identified profile")
                // register device to newly identified profile
                trackDeviceAttributes(token = existingDeviceToken)
            }
        }
    }

    /**
     * Common method to track an event with traits.
     * All other track methods should call this method to ensure consistency.
     */
    override fun <T> track(name: String, properties: T, serializationStrategy: SerializationStrategy<T>, enrichment: EnrichmentClosure?) {
        logger.debug("track an event with name $name and attributes $properties")
        analytics.track(name = name, properties = properties, serializationStrategy = serializationStrategy, enrichment = enrichment)
    }

    /**
     * Common method to track an screen with properties.
     * All other screen methods should call this method to ensure consistency.
     */
    override fun <T> screen(title: String, properties: T, serializationStrategy: SerializationStrategy<T>, enrichment: EnrichmentClosure?) {
        logger.debug("track a screen with title $title, properties $properties")
        analytics.screen(title = title, properties = properties, serializationStrategy = serializationStrategy, enrichment = enrichment)
    }

    override fun clearIdentify() {
        logger.info("resetting user profile with id ${this.userId}")

        logger.debug("deleting device token to remove device from user profile")

        deleteDeviceToken { event ->
            event?.apply { userId = this@CustomerIO.userId.toString() }
        }

        logger.debug("resetting user profile")
        analytics.reset()
    }

    override val registeredDeviceToken: String?
        get() = globalPreferenceStore.getDeviceToken()

    override val anonymousId: String
        get() = analytics.anonymousId()

    override val userId: String?
        get() = analytics.userId()

    override var deviceAttributes: CustomAttributes
        get() = emptyMap()
        set(value) {
            trackDeviceAttributes(registeredDeviceToken, value)
        }

    override fun registerDeviceToken(deviceToken: String, enrichment: EnrichmentClosure?) {
        if (deviceToken.isBlank()) {
            logger.debug("device token cannot be blank. ignoring request to register device token")
            return
        }

        logger.info("storing and registering device token $deviceToken for user profile: ${this.userId}")
        globalPreferenceStore.saveDeviceToken(deviceToken)

        trackDeviceAttributes(token = deviceToken, enrichment = enrichment)
    }

    private fun trackDeviceAttributes(token: String?, customAddedAttributes: CustomAttributes = emptyMap(), enrichment: EnrichmentClosure? = null) {
        if (token.isNullOrBlank()) {
            logger.debug("no device token found. ignoring request to track device.")
            return
        }

        val existingDeviceToken = contextPlugin.deviceToken
        if (existingDeviceToken != null && existingDeviceToken != token) {
            logger.debug("token has been refreshed, deleting old token to avoid registering same device multiple times")
            deleteDeviceToken()
        }

        val attributes = if (moduleConfig.autoTrackDeviceAttributes) {
            // order matters! allow customer to override default values if they wish.
            deviceStore.buildDeviceAttributes() + customAddedAttributes
        } else {
            customAddedAttributes
        }

        // Update plugin with updated device information
        contextPlugin.deviceToken = token

        logger.info("updating device attributes: $attributes")
        track(
            name = EventNames.DEVICE_UPDATE,
            properties = attributes,
            enrichment = enrichment ?: { event ->
                event?.apply { userId = this@CustomerIO.userId.toString() }
            }
        )
    }

    override fun deleteDeviceToken(enrichment: EnrichmentClosure?) {
        logger.info("deleting device token")

        val deviceToken = contextPlugin.deviceToken
        if (deviceToken.isNullOrBlank()) {
            logger.debug("No device token found to delete.")
            return
        }

        track(name = EventNames.DEVICE_DELETE, enrichment = enrichment)
    }

    override fun trackMetric(event: TrackMetric, enrichment: EnrichmentClosure?) {
        logger.info("${event.type} metric received for ${event.metric} event")
        logger.debug("tracking ${event.type} metric event with properties $event")

        track(name = EventNames.METRIC_DELIVERY, properties = event.asMap(), enrichment = enrichment)
    }

    companion object {
        /**
         * Module identifier for DataPipelines module.
         */
        internal const val MODULE_NAME = "DataPipelines"

        /**
         * Singleton instance of CustomerIO SDK that is created and set using the provided implementation.
         */
        @Volatile
        private var instance: CustomerIO? = null

        /**
         * Returns the instance of CustomerIO SDK.
         * If the instance is not initialized, it will throw an exception.
         * Please ensure that the SDK is initialized before calling this method.
         */
        @JvmStatic
        fun instance(): CustomerIO {
            return instance ?: throw IllegalStateException(
                "CustomerIO is not initialized. CustomerIOBuilder::build() must be called before obtaining SDK instance."
            )
        }

        /**
         * Creates and sets new instance of CustomerIO SDK using the provided implementation.
         * If the instance is already initialized, it will log an error and skip the initialization.
         * This method should be called only once during the application lifecycle using the provided builder.
         */
        @Synchronized
        @InternalCustomerIOApi
        fun createInstance(
            androidSDKComponent: AndroidSDKComponent,
            moduleConfig: DataPipelinesModuleConfig
        ): CustomerIO {
            val logger = SDKComponent.logger

            val existingInstance = instance
            if (existingInstance != null) {
                logger.error("CustomerIO instance is already initialized, skipping the initialization.")
                return existingInstance
            }

            logger.debug("creating new instance of CustomerIO SDK.")
            return CustomerIO(
                androidSDKComponent = androidSDKComponent,
                moduleConfig = moduleConfig,
                overrideAnalytics = SDKComponent.analyticsFactory?.invoke(moduleConfig)
            ).apply { instance = this }
        }

        /**
         * Clears the instance of CustomerIO SDK.
         * This method is used for testing purposes only and should not be used in production.
         */
        @InternalCustomerIOApi
        @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
        fun clearInstance() {
            // Reset SDKComponent to clear static references and avoid memory leaks
            SDKComponent.reset()
            instance = null
        }
    }
}

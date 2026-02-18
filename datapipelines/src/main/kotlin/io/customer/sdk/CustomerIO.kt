package io.customer.sdk

import androidx.annotation.VisibleForTesting
import com.segment.analytics.kotlin.android.Analytics
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.ErrorHandler
import com.segment.analytics.kotlin.core.emptyJsonObject
import com.segment.analytics.kotlin.core.platform.EnrichmentClosure
import com.segment.analytics.kotlin.core.platform.plugins.logger.LogKind
import com.segment.analytics.kotlin.core.platform.plugins.logger.LogMessage
import com.segment.analytics.kotlin.core.utilities.JsonAnySerializer
import com.segment.analytics.kotlin.core.utilities.putInContextUnderKey
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.datapipelines.di.analyticsFactory
import io.customer.datapipelines.di.dataPipelinesLogger
import io.customer.datapipelines.extensions.asMap
import io.customer.datapipelines.extensions.sanitizeForJson
import io.customer.datapipelines.extensions.type
import io.customer.datapipelines.extensions.updateAnalyticsConfig
import io.customer.datapipelines.migration.TrackingMigrationProcessor
import io.customer.datapipelines.plugins.ApplicationLifecyclePlugin
import io.customer.datapipelines.plugins.AutoTrackDeviceAttributesPlugin
import io.customer.datapipelines.plugins.AutomaticActivityScreenTrackingPlugin
import io.customer.datapipelines.plugins.AutomaticApplicationLifecycleTrackingPlugin
import io.customer.datapipelines.plugins.ContextPlugin
import io.customer.datapipelines.plugins.CustomerIODestination
import io.customer.datapipelines.plugins.LocationPlugin
import io.customer.datapipelines.plugins.ScreenFilterPlugin
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.subscribe
import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.model.Settings
import io.customer.sdk.events.TrackMetric
import io.customer.sdk.util.EventNames
import io.customer.tracking.migration.MigrationProcessor
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer

/**
 * Welcome to the Customer.io Android SDK!
 * This class is where you begin to use the SDK.
 * You must have an instance of `CustomerIO` to use the features of the SDK.
 * Create your own instance using
 * ```
 * val config = CustomerIOConfigBuilder(appContext, "your-api-key")
 *   .logLevel(CioLogLevel.DEBUG)
 *   .addCustomerIOModule(...)
 *   .build()
 *
 * CustomerIO.initialize(config)
 * val customerIO = CustomerIO.instance()
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
    private val dataPipelinesLogger: DataPipelinesLogger = SDKComponent.dataPipelinesLogger
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
    private val locationPlugin: LocationPlugin = LocationPlugin(logger)

    init {
        // Set analytics logger and debug logs based on SDK logger configuration
        Analytics.debugLogsEnabled = logger.logLevel == CioLogLevel.DEBUG
        Analytics.setLogger(segmentLogger)

        // Add required plugins to analytics instance
        analytics.add(contextPlugin)

        if (moduleConfig.autoAddCustomerIODestination) {
            analytics.add(CustomerIODestination())
        }

        // Add auto track device attributes plugin only if enabled in config
        if (moduleConfig.autoTrackDeviceAttributes) {
            analytics.add(AutoTrackDeviceAttributesPlugin())
        }

        // Add plugin to filter events based on SDK configuration
        analytics.add(ScreenFilterPlugin(moduleConfig.screenViewUse))
        analytics.add(locationPlugin)
        analytics.add(ApplicationLifecyclePlugin())

        // subscribe to journey events emitted from push/in-app module to send them via data pipelines
        subscribeToJourneyEvents()
        // republish profile/anonymous events for late-added modules
        postUserIdentificationEvents()
    }

    private fun postUserIdentificationEvents() {
        val userId = analytics.userId()
        val anonymousId = analytics.anonymousId()
        eventBus.publish(Event.UserChangedEvent(userId = userId, anonymousId = anonymousId))
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
        eventBus.subscribe<Event.TrackLocationEvent> {
            trackLocation(it)
        }
    }

    private fun trackLocation(event: Event.TrackLocationEvent) {
        val location = event.location
        logger.debug("tracking location update: lat=${location.latitude}, lng=${location.longitude}")

        // Cache location for enriching future identify events
        locationPlugin.lastLocation = location

        track(
            name = EventNames.LOCATION_UPDATE,
            properties = mapOf(
                "lat" to location.latitude,
                "lng" to location.longitude,
                LocationPlugin.INTERNAL_LOCATION_KEY to true
            )
        )
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

        // save settings to storage
        analytics.configuration.let { config ->
            val settings = Settings(writeKey = config.writeKey, apiHost = config.apiHost)
            globalPreferenceStore.saveSettings(settings)
        }

        // add plugins to analytics instance
        // this is done after the initialization to ensure the SDK has been initialized, since these plugins
        // utilize SDK on setup rather than events
        if (moduleConfig.autoTrackActivityScreens) {
            analytics.add(AutomaticActivityScreenTrackingPlugin())
        }

        if (moduleConfig.trackApplicationLifecycleEvents) {
            analytics.add(AutomaticApplicationLifecycleTrackingPlugin())
        }
    }

    @Deprecated("Use setProfileAttributes() function instead")
    @set:JvmName("setProfileAttributesDeprecated")
    override var profileAttributes: CustomAttributes
        get() = analytics.traits() ?: emptyMap()
        set(value) {
            setProfileAttributes(value)
        }

    override fun setProfileAttributes(attributes: CustomAttributes) {
        val identifier = this.userId
        if (identifier != null) {
            identify(userId = identifier, traits = attributes)
        } else {
            logger.debug("No user profile found, updating sanitized traits for anonymous user ${analytics.anonymousId()}")
            analytics.identify(traits = attributes.sanitizeForJson())
        }
    }

    /**
     * Common method to identify a user profile with traits.
     * The method is responsible for identifying the user profile with the given traits
     * and running any necessary hooks.
     * All other identify methods should call this method to ensure consistency.
     */
    override fun <Traits> identifyImpl(
        userId: String,
        traits: Traits,
        serializationStrategy: SerializationStrategy<Traits>
    ) {
        if (userId.isBlank()) {
            logger.debug("Profile cannot be identified: Identifier is blank. Please retry with a valid, non-empty identifier.")
            return
        }

        // this is the current userId that is identified in the SDK
        val currentlyIdentifiedProfile = this.userId.takeUnless { it.isNullOrBlank() }
        val isChangingIdentifiedProfile = currentlyIdentifiedProfile != null && currentlyIdentifiedProfile != userId
        val isFirstTimeIdentifying = currentlyIdentifiedProfile == null

        if (isChangingIdentifiedProfile) {
            logger.info("changing profile from id $currentlyIdentifiedProfile to $userId")
            if (registeredDeviceToken != null) {
                dataPipelinesLogger.logDeletingTokenDueToNewProfileIdentification()
                deleteDeviceToken { event ->
                    event?.apply {
                        currentlyIdentifiedProfile?.let { this.userId = it }
                    }
                }
            }
        }

        logger.info("identify profile with identifier $userId and traits $traits")
        // publish event to EventBus for other modules to consume
        eventBus.publish(Event.UserChangedEvent(userId = userId, anonymousId = analytics.anonymousId()))
        analytics.identify(
            userId = userId,
            traits = traits,
            serializationStrategy = serializationStrategy
        )

        if (isFirstTimeIdentifying || isChangingIdentifiedProfile) {
            logger.debug("first time identified or changing identified profile")
            val existingDeviceToken = registeredDeviceToken
            if (existingDeviceToken != null) {
                dataPipelinesLogger.automaticTokenRegistrationForNewProfile(existingDeviceToken, userId)
                // register device to newly identified profile
                trackDeviceAttributes(token = existingDeviceToken)
            }
        }
    }

    /**
     * Common method to track an event with traits.
     * All other track methods should call this method to ensure consistency.
     */
    override fun <T> trackImpl(name: String, properties: T, serializationStrategy: SerializationStrategy<T>) = track(name, properties, serializationStrategy, null)

    /**
     * Private method that support enrichment of generated track events.
     */
    private fun <T> track(name: String, properties: T, serializationStrategy: SerializationStrategy<T>, enrichment: EnrichmentClosure?) {
        logger.debug("track an event with name $name and attributes $properties")
        analytics.track(name = name, properties = properties, serializationStrategy = serializationStrategy, enrichment = enrichment)
    }

    /**
     * Common method to track an screen with properties.
     * All other screen methods should call this method to ensure consistency.
     */
    override fun <T> screenImpl(title: String, properties: T, serializationStrategy: SerializationStrategy<T>) {
        logger.debug("track a screen with title $title, properties $properties")
        eventBus.publish(Event.ScreenViewedEvent(name = title))
        analytics.screen(title = title, properties = properties, serializationStrategy = serializationStrategy)
    }

    override fun clearIdentifyImpl() {
        logger.info("resetting user profile with id ${this.userId}")

        logger.debug("deleting device token to remove device from user profile")

        // since the tasks are asynchronous, we need to store the userId before deleting the device token
        // otherwise, the userId could be null when the delete task is executed
        val existingUserId = userId
        deleteDeviceToken { event ->
            event?.apply { userId = existingUserId.toString() }
        }

        logger.debug("resetting user profile")
        // publish event to EventBus for other modules to consume
        eventBus.publish(Event.ResetEvent)
        analytics.reset()

        val newAnonymousId = analytics.anonymousId()
        eventBus.publish(Event.UserChangedEvent(userId = null, anonymousId = newAnonymousId))
    }

    override val registeredDeviceToken: String?
        get() = globalPreferenceStore.getDeviceToken()

    override val anonymousId: String
        get() = analytics.anonymousId()

    override val userId: String?
        get() = analytics.userId()

    @Deprecated("Use setDeviceAttributes() function instead")
    @set:JvmName("setDeviceAttributesDeprecated")
    override var deviceAttributes: CustomAttributes
        get() = emptyMap()
        set(value) {
            setDeviceAttributes(value)
        }

    override fun setDeviceAttributes(attributes: CustomAttributes) {
        trackDeviceAttributes(registeredDeviceToken, attributes)
    }

    override fun registerDeviceTokenImpl(deviceToken: String) {
        if (deviceToken.isBlank()) {
            dataPipelinesLogger.logStoringBlankPushToken()
            return
        }

        dataPipelinesLogger.logStoringDevicePushToken(deviceToken, this.userId)
        globalPreferenceStore.saveDeviceToken(deviceToken)

        dataPipelinesLogger.logRegisteringPushToken(deviceToken, this.userId)
        trackDeviceAttributes(token = deviceToken)
    }

    private fun trackDeviceAttributes(token: String?, customAddedAttributes: CustomAttributes = emptyMap()) {
        if (token.isNullOrBlank()) {
            dataPipelinesLogger.logTrackingDevicesAttributesWithoutValidToken()
            return
        }

        val existingDeviceToken = contextPlugin.deviceToken
        if (existingDeviceToken != null && existingDeviceToken != token) {
            dataPipelinesLogger.logPushTokenRefreshed()
            deleteDeviceToken { event ->
                event?.putInContextUnderKey("device", "token", existingDeviceToken)
            }
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
            properties = attributes
        )
    }

    override fun deleteDeviceTokenImpl() = deleteDeviceToken(null)

    private fun deleteDeviceToken(enrichment: EnrichmentClosure?) {
        logger.info("deleting device token")

        val deviceToken = contextPlugin.deviceToken
        if (deviceToken.isNullOrBlank()) {
            logger.debug("No device token found to delete.")
            return
        }

        track(name = EventNames.DEVICE_DELETE, properties = emptyJsonObject, serializationStrategy = JsonAnySerializer.serializersModule.serializer(), enrichment = enrichment)
    }

    override fun trackMetricImpl(event: TrackMetric) {
        logger.info("${event.type} metric received for ${event.metric} event")
        logger.debug("tracking ${event.type} metric event with properties $event")

        track(name = EventNames.METRIC_DELIVERY, properties = event.asMap())
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
                "CustomerIO is not initialized. CustomerIO.initialize() must be called before obtaining SDK instance."
            )
        }

        /**
         * Initialize the CustomerIO SDK with the provided configuration.
         * This method should be called once during the application lifecycle, typically in Application.onCreate().
         * After initialization, use CustomerIO.instance() to get the initialized SDK instance.
         *
         * Example usage:
         * ```
         * val config = CustomerIOConfigBuilder(this, "your-api-key")
         *     .logLevel(CioLogLevel.DEBUG)
         *     .region(Region.EU)
         *     .build()
         *
         * CustomerIO.initialize(config)
         * val customerIO = CustomerIO.instance()
         * ```
         *
         * @param config The configuration for initializing the CustomerIO SDK
         */
        @JvmStatic
        fun initialize(config: CustomerIOConfig) {
            val androidSDKComponent = SDKComponent.android()

            val modules = SDKComponent.modules
            val logger = SDKComponent.dataPipelinesLogger

            // Update the log level for the SDK
            SDKComponent.logger.logLevel = config.logLevel

            logger.coreSdkInitStart()

            // Initialize DataPipelinesModule with the provided configuration
            val dataPipelinesConfig = DataPipelinesModuleConfig(
                cdpApiKey = config.cdpApiKey,
                region = config.region,
                apiHostOverride = config.apiHost,
                cdnHostOverride = config.cdnHost,
                flushAt = config.flushAt,
                flushInterval = config.flushInterval,
                flushPolicies = config.flushPolicies,
                autoAddCustomerIODestination = config.autoAddCustomerIODestination,
                trackApplicationLifecycleEvents = config.trackApplicationLifecycleEvents,
                autoTrackDeviceAttributes = config.autoTrackDeviceAttributes,
                autoTrackActivityScreens = config.autoTrackActivityScreens,
                migrationSiteId = config.migrationSiteId,
                screenViewUse = config.screenViewUse
            )

            // Initialize CustomerIO instance before initializing the modules
            val customerIO = createInstance(
                androidSDKComponent = androidSDKComponent,
                moduleConfig = dataPipelinesConfig
            )

            // Register DataPipelines module and all other modules with SDKComponent
            modules[MODULE_NAME] = customerIO
            modules.putAll(config.modules.associateBy { module -> module.moduleName })

            modules.forEach { (_, module) ->
                logger.moduleInitStart(module)
                module.initialize()
                logger.moduleInitSuccess(module)
            }

            logger.coreSdkInitSuccess()
        }

        /**
         * Creates and sets new instance of CustomerIO SDK using the provided implementation.
         * If the instance is already initialized, it will log an error and skip the initialization.
         * This method should be called only once during the application lifecycle using the provided builder.
         */
        @Synchronized
        @InternalCustomerIOApi
        private fun createInstance(
            androidSDKComponent: AndroidSDKComponent,
            moduleConfig: DataPipelinesModuleConfig
        ): CustomerIO {
            val logger = SDKComponent.dataPipelinesLogger

            val existingInstance = instance
            if (existingInstance != null) {
                logger.coreSdkAlreadyInitialized()
                return existingInstance
            }

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

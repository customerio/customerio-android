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
import io.customer.datapipelines.plugins.IdentifyContextPlugin
import io.customer.datapipelines.plugins.ScreenFilterPlugin
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.subscribe
import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.pipeline.DataPipeline
import io.customer.sdk.core.pipeline.identifyHookRegistry
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
 *
 * **Pre-init event buffering**
 *
 * Event-shaped public-API calls (`track`, `identify`, `screen`, …) made on
 * [instance] **before** [initialize] completes are absorbed into a bounded
 * FIFO (capacity 100, drop-most-recent on overflow) and replayed in order
 * once initialization finishes. This eliminates the legacy
 * `IllegalStateException` thrown by pre-init `instance()` calls and ensures
 * cold-start / deep-link events fired by host apps and wrapper SDKs aren't
 * silently lost.
 *
 * Read-side accessors (`userId`, `anonymousId`, `registeredDeviceToken`,
 * `profileAttributes`, `deviceAttributes`) return safe defaults (`null`,
 * empty string, empty map) until initialization completes.
 */
class CustomerIO private constructor() :
    CustomerIOModule<DataPipelinesModuleConfig>, DataPipelineInstance(), DataPipeline {
    override val moduleName: String = MODULE_NAME

    private val logger: Logger = SDKComponent.logger
    private val dataPipelinesLogger: DataPipelinesLogger = SDKComponent.dataPipelinesLogger
    private val eventBus = SDKComponent.eventBus

    // Pre-init buffer: absorbs event-shaped calls invoked before
    // initializeComponents() runs and replays them in order on init.
    private val preInitBuffer = PreInitEventBuffer()

    @Volatile
    private var componentsReady: Boolean = false
    private val isReady: Boolean get() = componentsReady

    // Lateinit runtime components — populated by initializeComponents().
    // Access guarded by isReady; callers that route through a public method
    // either execute (post-init) or enqueue into preInitBuffer (pre-init).
    private lateinit var _moduleConfig: DataPipelinesModuleConfig
    override val moduleConfig: DataPipelinesModuleConfig
        get() = _moduleConfig

    private lateinit var globalPreferenceStore: io.customer.sdk.data.store.GlobalPreferenceStore
    private lateinit var deviceStore: io.customer.sdk.data.store.DeviceStore
    internal lateinit var analytics: Analytics
    private lateinit var contextPlugin: ContextPlugin
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

    /**
     * Populate runtime components and transition the singleton from
     * buffering mode to ready mode. Called exactly once from
     * [Companion.createInstance] when [Companion.initialize] runs.
     *
     * Buffered events are drained synchronously at the end of this method
     * so that the first post-init call observes a non-empty pipeline state.
     */
    @Synchronized
    private fun initializeComponents(
        androidSDKComponent: AndroidSDKComponent,
        moduleConfig: DataPipelinesModuleConfig,
        overrideAnalytics: Analytics? = null
    ) {
        if (componentsReady) return

        this._moduleConfig = moduleConfig
        this.globalPreferenceStore = androidSDKComponent.globalPreferenceStore
        this.deviceStore = androidSDKComponent.deviceStore

        this.analytics = overrideAnalytics ?: Analytics(
            writeKey = moduleConfig.cdpApiKey,
            context = androidSDKComponent.applicationContext,
            configs = updateAnalyticsConfig(
                moduleConfig = moduleConfig,
                errorHandler = errorLogger
            )
        )

        this.contextPlugin = ContextPlugin(deviceStore)

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
        analytics.add(IdentifyContextPlugin(SDKComponent.identifyHookRegistry, logger))
        analytics.add(ApplicationLifecyclePlugin())

        // Register this instance as DataPipeline so modules can send track events directly
        SDKComponent.registerDependency<DataPipeline> { this }

        // subscribe to journey events emitted from push/in-app module to send them via data pipelines
        subscribeToJourneyEvents()
        // republish profile/anonymous events for late-added modules
        postUserIdentificationEvents()

        // Mark ready BEFORE draining so replayed events take the real-impl path.
        componentsReady = true

        // Drain any pre-init buffered calls in order against this now-ready instance.
        preInitBuffer.transitionToReady(this)
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
        get() = if (isReady) analytics.traits() ?: emptyMap() else emptyMap()
        set(value) {
            setProfileAttributes(value)
        }

    override fun setProfileAttributes(attributes: CustomAttributes) {
        if (!isReady) {
            preInitBuffer.enqueue { it.setProfileAttributes(attributes) }
            return
        }
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
    @Suppress("DEPRECATION")
    override fun <Traits> identifyImpl(
        userId: String,
        traits: Traits,
        serializationStrategy: SerializationStrategy<Traits>
    ) {
        if (!isReady) {
            preInitBuffer.enqueue { it.identify(userId, traits, serializationStrategy) }
            return
        }
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

        analytics.identify(
            userId = userId,
            traits = traits,
            serializationStrategy = serializationStrategy
        )
        // publish event to EventBus for other modules to consume
        // Must come after analytics.identify() so that analytics.userId() returns the
        // new userId when downstream subscribers (e.g. location resync) gate on it.
        eventBus.publish(Event.UserChangedEvent(userId = userId, anonymousId = analytics.anonymousId()))

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
    @Suppress("DEPRECATION")
    override fun <T> trackImpl(name: String, properties: T, serializationStrategy: SerializationStrategy<T>) {
        if (!isReady) {
            preInitBuffer.enqueue { it.track(name, properties, serializationStrategy) }
            return
        }
        track(name, properties, serializationStrategy, null)
    }

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
    @Suppress("DEPRECATION")
    override fun <T> screenImpl(title: String, properties: T, serializationStrategy: SerializationStrategy<T>) {
        if (!isReady) {
            preInitBuffer.enqueue { it.screen(title, properties, serializationStrategy) }
            return
        }
        logger.debug("track a screen with title $title, properties $properties")
        eventBus.publish(Event.ScreenViewedEvent(name = title))
        analytics.screen(title = title, properties = properties, serializationStrategy = serializationStrategy)
    }

    override fun clearIdentifyImpl() {
        if (!isReady) {
            preInitBuffer.enqueue { it.clearIdentify() }
            return
        }
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
        get() = if (isReady) globalPreferenceStore.getDeviceToken() else null

    override val anonymousId: String
        get() = if (isReady) analytics.anonymousId() else ""

    override val userId: String?
        get() = if (isReady) analytics.userId() else null

    override val isUserIdentified: Boolean
        get() = if (isReady) !analytics.userId().isNullOrEmpty() else false

    @Deprecated("Use setDeviceAttributes() function instead")
    @set:JvmName("setDeviceAttributesDeprecated")
    override var deviceAttributes: CustomAttributes
        get() = emptyMap()
        set(value) {
            setDeviceAttributes(value)
        }

    override fun setDeviceAttributes(attributes: CustomAttributes) {
        if (!isReady) {
            preInitBuffer.enqueue { it.setDeviceAttributes(attributes) }
            return
        }
        trackDeviceAttributes(registeredDeviceToken, attributes)
    }

    override fun registerDeviceTokenImpl(deviceToken: String) {
        if (!isReady) {
            preInitBuffer.enqueue { it.registerDeviceToken(deviceToken) }
            return
        }
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

    override fun deleteDeviceTokenImpl() {
        if (!isReady) {
            preInitBuffer.enqueue { it.deleteDeviceToken() }
            return
        }
        deleteDeviceToken(null)
    }

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
        if (!isReady) {
            preInitBuffer.enqueue { it.trackMetric(event) }
            return
        }
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
         * Singleton instance of CustomerIO SDK.
         *
         * Lazily created on first [instance] call so that events fired
         * before [initialize] are absorbed into the singleton's pre-init
         * buffer rather than throwing.
         */
        @Volatile
        private var _instance: CustomerIO? = null

        /**
         * Returns the singleton instance of CustomerIO SDK.
         *
         * Safe to call before [initialize]. Pre-init, returns a singleton
         * whose event-shaped methods buffer calls (cap 100, drop-most-recent
         * on overflow) and replay them in order once [initialize] completes.
         * Read-side properties return safe defaults (`null` / empty string /
         * empty map) until initialization completes.
         */
        @JvmStatic
        fun instance(): CustomerIO {
            return _instance ?: synchronized(this) {
                _instance ?: CustomerIO().also { _instance = it }
            }
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
         * Wires real components into the singleton CustomerIO instance,
         * transitioning it from buffering mode to ready mode. If the
         * singleton is already in ready mode, logs and returns it as-is.
         */
        @Synchronized
        @InternalCustomerIOApi
        private fun createInstance(
            androidSDKComponent: AndroidSDKComponent,
            moduleConfig: DataPipelinesModuleConfig
        ): CustomerIO {
            val logger = SDKComponent.dataPipelinesLogger
            val cio = instance()
            if (cio.componentsReady) {
                logger.coreSdkAlreadyInitialized()
                return cio
            }
            cio.initializeComponents(
                androidSDKComponent = androidSDKComponent,
                moduleConfig = moduleConfig,
                overrideAnalytics = SDKComponent.analyticsFactory?.invoke(moduleConfig)
            )
            return cio
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
            // Discard the singleton so the next instance() call returns a fresh
            // pre-init CustomerIO. Any pre-init buffer state is dropped.
            _instance = null
        }
    }
}

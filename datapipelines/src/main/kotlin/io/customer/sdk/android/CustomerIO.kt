// TODO: Move this class and its dependencies (CustomerIOInstance, DataPipelineInstance) to the correct package.
// We need to move this class to the right package to avoid breaking imports for the users of the SDK.
// We have placed the class in the wrong package for now to avoid breaking the build.
// Once old implementations are removed, we can move the class to the correct package.
package io.customer.sdk.android

import androidx.annotation.VisibleForTesting
import com.segment.analytics.kotlin.android.Analytics
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.ErrorHandler
import com.segment.analytics.kotlin.core.platform.plugins.logger.LogKind
import com.segment.analytics.kotlin.core.platform.plugins.logger.LogMessage
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.datapipelines.plugins.CustomerIODestination
import io.customer.sdk.DataPipelineInstance
import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.model.CustomAttributes
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
    override val moduleConfig: DataPipelinesModuleConfig
) : CustomerIOModule<DataPipelinesModuleConfig>, DataPipelineInstance() {
    override val moduleName: String = MODULE_NAME

    private val logger: Logger = SDKComponent.logger

    // Display logs under the CIO tag for easier filtering in logcat
    private val errorLogger = object : ErrorHandler {
        override fun invoke(error: Throwable) {
            logger.error(error.message ?: error.stackTraceToString())
        }
    }

    // Logger implementation for Segment logger to display logs under CIO tag for easier filtering in logcat
    private val segmentLogger = object : com.segment.analytics.kotlin.core.platform.plugins.logger.Logger {
        override fun parseLog(log: LogMessage) {
            val message = log.message
            when (log.kind) {
                LogKind.ERROR -> logger.error(message)
                LogKind.WARNING -> logger.info(message)
                LogKind.DEBUG -> logger.debug(message)
            }
        }
    }

    private val analytics: Analytics = Analytics(
        writeKey = moduleConfig.cdpApiKey,
        context = androidSDKComponent.applicationContext
    ) {
        flushAt = moduleConfig.flushAt
        flushInterval = moduleConfig.flushInterval
        flushPolicies = moduleConfig.flushPolicies
        // Force set to false as we don't need to forward events to Segment destination
        // User can disable CIO destination to achieve same results
        autoAddSegmentDestination = false
        trackApplicationLifecycleEvents = moduleConfig.trackApplicationLifecycleEvents
        apiHost = moduleConfig.apiHost
        cdnHost = moduleConfig.cdnHost
        errorHandler = errorLogger
    }

    init {
        // Set analytics logger and debug logs based on SDK logger configuration
        Analytics.debugLogsEnabled = logger.logLevel == CioLogLevel.DEBUG
        Analytics.setLogger(segmentLogger)

        if (moduleConfig.autoAddCustomerIODestination) {
            analytics.add(CustomerIODestination())
        }
    }

    override fun initialize() {
        logger.debug("CustomerIO SDK initialized with DataPipelines module.")
    }

    // Gets the userId registered by a previous identify call
    // or null if no user is registered
    private val registeredUserId: String? get() = analytics.userId()

    override var profileAttributes: CustomAttributes
        get() = analytics.traits() ?: emptyMap()
        set(value) {
            val userId = registeredUserId
            if (userId != null) {
                identify(userId = userId, traits = value)
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
        serializationStrategy: SerializationStrategy<Traits>
    ) {
        if (userId.isBlank()) {
            logger.debug("Profile cannot be identified: Identifier is blank. Please retry with a valid, non-empty identifier.")
            return
        }

        logger.info("identify profile with identifier $userId and traits $traits")
        analytics.identify(
            userId = userId,
            traits = traits,
            serializationStrategy = serializationStrategy
        )
    }

    /**
     * Common method to track an event with traits.
     * All other track methods should call this method to ensure consistency.
     */
    override fun <T> track(name: String, properties: T, serializationStrategy: SerializationStrategy<T>) {
        logger.debug("track an event with name $name and attributes $properties")
        analytics.track(name = name, properties = properties, serializationStrategy = serializationStrategy)
    }

    /**
     * Common method to track an screen with properties.
     * All other screen methods should call this method to ensure consistency.
     */
    override fun <T> screen(name: String, properties: T, serializationStrategy: SerializationStrategy<T>) {
        logger.debug("track a screen with title $name, properties $properties")
        analytics.screen(title = name, properties = properties, serializationStrategy = serializationStrategy)
    }

    override fun clearIdentify() {
        val userId = registeredUserId ?: "anonymous"
        logger.debug("resetting user profile with id $userId")
        analytics.reset()
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
                moduleConfig = moduleConfig
            ).apply { instance = this }
        }

        /**
         * Clears the instance of CustomerIO SDK.
         * This method is used for testing purposes only and should not be used in production.
         */
        @InternalCustomerIOApi
        @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
        fun clearInstance() {
            instance = null
        }
    }
}

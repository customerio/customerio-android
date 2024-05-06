package io.customer.datapipelines

import com.segment.analytics.kotlin.android.Analytics
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.ErrorHandler
import com.segment.analytics.kotlin.core.platform.plugins.logger.LogKind
import com.segment.analytics.kotlin.core.platform.plugins.logger.LogMessage
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
 * DataPipelinesModule is SDK module that provides the ability to send data to
 * Customer.io using data pipelines.
 */
class DataPipelinesModule
internal constructor(
    androidSDKComponent: AndroidSDKComponent,
    override val moduleConfig: DataPipelinesModuleConfig
) : CustomerIOModule<DataPipelinesModuleConfig>, DataPipelineInstance {
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
    }

    // Gets the userId registered by a previous identify call
    // or null if no user is registered
    private val registeredUserId: String? get() = analytics.userId()

    /**
     * Common method to identify a user profile with traits.
     * The method is responsible for identifying the user profile with the given traits
     * and running any necessary hooks.
     * All other identify methods should call this method to ensure consistency.
     * For methods that don't require to mention traits, use [Nothing] as the type.
     */
    private fun <Traits> commonIdentify(
        userId: String,
        traitsMap: CustomAttributes? = null,
        traitsSerializable: Pair<Traits, SerializationStrategy<Traits>>? = null
    ) {
        if (userId.isBlank()) {
            logger.debug("Profile cannot be identified: Identifier is blank. Please retry with a valid, non-empty identifier.")
            return
        }

        logger.info("identify profile with id $userId")
        when {
            traitsMap != null -> {
                logger.debug("identify profile with traits $traitsMap")
                analytics.identify(
                    userId = userId,
                    traits = traitsMap
                )
            }

            traitsSerializable != null -> {
                logger.debug("identify profile with traits $traitsSerializable")
                analytics.identify(
                    userId = userId,
                    traits = traitsSerializable.first,
                    serializationStrategy = traitsSerializable.second
                )
            }

            else -> {
                analytics.identify(userId = userId)
            }
        }
    }

    override var profileAttributes: CustomAttributes
        get() = analytics.traits() ?: emptyMap()
        set(value) {
            val userId = registeredUserId
            if (userId != null) {
                commonIdentify<Nothing>(userId = userId, traitsMap = value)
            } else {
                logger.debug("No user profile found, updating traits for anonymous user ${analytics.anonymousId()}")
                analytics.identify(traits = value)
            }
        }

    override fun identify(userId: String) {
        commonIdentify<Nothing>(userId = userId)
    }

    override fun identify(userId: String, traits: CustomAttributes) {
        commonIdentify<Nothing>(userId = userId, traitsMap = traits)
    }

    override fun <Body> identify(
        userId: String,
        traits: Body,
        serializationStrategy: SerializationStrategy<Body>
    ) = commonIdentify(userId = userId, traitsSerializable = traits to serializationStrategy)

    override fun clearIdentify() {
        val userId = registeredUserId ?: "anonymous"
        logger.debug("resetting user profile with id $userId")
        analytics.reset()
    }

    companion object {
        internal const val MODULE_NAME = "DataPipelines"
    }
}

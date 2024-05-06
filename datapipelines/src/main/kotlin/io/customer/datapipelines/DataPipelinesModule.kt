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

    companion object {
        internal const val MODULE_NAME = "DataPipelines"
    }
}

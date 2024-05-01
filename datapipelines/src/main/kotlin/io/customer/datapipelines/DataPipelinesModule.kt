package io.customer.datapipelines

import com.segment.analytics.kotlin.android.Analytics
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.ErrorHandler
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.datapipelines.extensions.apiHost
import io.customer.datapipelines.extensions.cdnHost
import io.customer.datapipelines.plugins.CustomerIODestination
import io.customer.sdk.android.CustomerIOInstance
import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModule

/**
 * DataPipelinesModule is SDK module that provides the ability to send data to
 * Customer.io using data pipelines.
 */
class DataPipelinesModule
internal constructor(
    androidSDKComponent: AndroidSDKComponent,
    override val moduleConfig: DataPipelinesModuleConfig
) : CustomerIOModule<DataPipelinesModuleConfig>, CustomerIOInstance {
    override val moduleName: String = MODULE_NAME

    // Display logs under the CIO tag for easier filtering in logcat
    private val errorLogger = object : ErrorHandler {
        override fun invoke(error: Throwable) {
            SDKComponent.logger.error(error.message ?: error.stackTraceToString())
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

package io.customer.datapipelines

import com.segment.analytics.kotlin.android.Analytics
import com.segment.analytics.kotlin.core.Analytics
import io.customer.android.core.di.AndroidSDKComponent
import io.customer.android.core.module.CustomerIOModule
import io.customer.android.sdk.CustomerIOInstance
import io.customer.datapipelines.config.DataPipelinesModuleConfig

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

    private val analytics: Analytics

    init {
        analytics = Analytics(
            writeKey = moduleConfig.cdpApiKey,
            context = androidSDKComponent.applicationContext
        )
    }

    override fun initialize() {
    }

    companion object {
        internal const val MODULE_NAME = "DataPipelines"
    }
}

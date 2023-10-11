package io.customer.datapipeline

import android.app.Application
import com.segment.analytics.kotlin.core.Configuration

class DataPipelineModuleConfig private constructor(
    val writeKey: String,
    val application: Application,
    val configuration: Configuration.() -> Unit = {}
) {
    class Builder(
        private val writeKey: String,
        val application: Application,
        val configuration: Configuration.() -> Unit = {}
    ) {
        fun build(): DataPipelineModuleConfig {
            return DataPipelineModuleConfig(
                writeKey = writeKey,
                application = application,
                configuration = configuration
            )
        }
    }
}

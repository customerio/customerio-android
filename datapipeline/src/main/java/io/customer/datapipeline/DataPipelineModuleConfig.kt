package io.customer.datapipeline

import io.customer.sdk.module.CustomerIOModuleConfig

class DataPipelineModuleConfig private constructor(
    val apiKey: String
) : CustomerIOModuleConfig {

    class Builder(val apiKey: String) : CustomerIOModuleConfig.Builder<DataPipelineModuleConfig> {
        override fun build(): DataPipelineModuleConfig {
            return DataPipelineModuleConfig(
                apiKey = apiKey
            )
        }
    }
}

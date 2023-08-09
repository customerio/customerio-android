package io.customer.datapipeline.di

import io.customer.datapipeline.ModuleDataPipeline
import io.customer.sdk.CustomerIO

fun CustomerIO.dataPipeline(): ModuleDataPipeline {
    return diGraph.sdkConfig.modules[ModuleDataPipeline.moduleName] as? ModuleDataPipeline
        ?: throw IllegalStateException("ModuleDataPipeline not initialized")
}

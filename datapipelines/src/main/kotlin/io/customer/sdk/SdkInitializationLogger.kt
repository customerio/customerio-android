package io.customer.sdk

import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.module.CustomerIOModuleConfig
import io.customer.sdk.core.util.Logger
import java.lang.IllegalStateException

internal class SdkInitializationLogger(private val logger: Logger) {

    companion object {
        const val TAG = "Init"
    }

    fun coreSdkInitStart() {
        logger.debug(
            tag = TAG,
            message = "Creating new instance of CustomerIO SDK version: ${Version.version}..."
        )
    }

    fun coreSdkAlreadyInitialized() {
        logger.error(
            tag = TAG,
            message = "CustomerIO instance is already initialized, skipping the initialization",
            throwable = IllegalStateException("CustomerIO SDK already initialized")
        )
    }

    fun coreSdkInitSuccess() {
        logger.info(
            tag = TAG,
            message = "CustomerIO SDK is initialized and ready to use"
        )
    }

    fun moduleInitStart(module: CustomerIOModule<out CustomerIOModuleConfig>) {
        logger.debug(
            tag = TAG,
            message = "Initializing SDK module ${module.moduleName} with config: ${module.moduleConfig}..."
        )
    }

    fun moduleInitSuccess(module: CustomerIOModule<out CustomerIOModuleConfig>) {
        logger.info(
            tag = TAG,
            message = "CustomerIO ${module.moduleName} module is initialized and ready to use"
        )
    }
}

package io.customer.commontest.config

import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent

/**
 * TestConfig helper class that provides a DSL for configuring DiGraphs.
 */
class DIGraphConfiguration {
    var sdkComponent: ConfigDSL<SDKComponent> = {}
        private set
    var androidSDKComponent: ConfigDSL<AndroidSDKComponent> = {}
        private set

    operator fun plus(other: DIGraphConfiguration): DIGraphConfiguration {
        return DIGraphConfiguration().apply {
            sdkComponent = this@DIGraphConfiguration.sdkComponent + other.sdkComponent
            androidSDKComponent = this@DIGraphConfiguration.androidSDKComponent + other.androidSDKComponent
        }
    }

    fun sdk(block: ConfigDSL<SDKComponent>) {
        sdkComponent = block
    }

    fun android(block: ConfigDSL<AndroidSDKComponent>) {
        androidSDKComponent = block
    }
}

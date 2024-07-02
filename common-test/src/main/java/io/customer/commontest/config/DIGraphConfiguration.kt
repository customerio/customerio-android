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
        val copy = DIGraphConfiguration()
        copy.sdkComponent = sdkComponent + other.sdkComponent
        copy.androidSDKComponent = androidSDKComponent + other.androidSDKComponent
        return copy
    }

    fun sdk(block: ConfigDSL<SDKComponent>) {
        sdkComponent = block
    }

    fun android(block: ConfigDSL<AndroidSDKComponent>) {
        androidSDKComponent = block
    }
}

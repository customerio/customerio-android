package io.customer.commontest.config

import android.app.Application
import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.registerAndroidSDKComponent
import io.customer.sdk.data.store.Client

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

fun DIGraphConfiguration.setupAndroidSDKComponent(
    application: Application,
    client: Client = Client.Android(sdkVersion = "3.0.0")
) {
    // Because we are not initializing the SDK, we need to register the
    // Android SDK component manually so that the module can utilize it
    androidSDKComponent(SDKComponent.registerAndroidSDKComponent(application, client))
}

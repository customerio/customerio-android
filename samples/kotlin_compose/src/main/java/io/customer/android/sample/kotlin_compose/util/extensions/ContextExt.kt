package io.customer.android.sample.kotlin_compose.util.extensions

import io.customer.android.sample.kotlin_compose.BuildConfig
import io.customer.sdk.core.di.SDKComponent

fun getUserAgent(): String {
    return "Customer.io Android SDK ${SDKComponent.android().client.sdkVersion} Kotlin Compose ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}

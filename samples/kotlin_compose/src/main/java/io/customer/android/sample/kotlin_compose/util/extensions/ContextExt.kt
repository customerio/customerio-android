package io.customer.android.sample.kotlin_compose.util.extensions

import io.customer.android.sample.kotlin_compose.BuildConfig
import io.customer.sdk.CustomerIO

fun getUserAgent(): String {
    return "Customer.io Android SDK ${CustomerIO.instance().sdkVersion} Kotlin Compose ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}

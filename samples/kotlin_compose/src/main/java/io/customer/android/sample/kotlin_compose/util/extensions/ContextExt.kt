package io.customer.android.sample.kotlin_compose.util.extensions

import android.content.Context
import io.customer.android.sample.kotlin_compose.BuildConfig
import io.customer.android.sample.kotlin_compose.R
import io.customer.sdk.CustomerIO

fun Context.getUserAgent(): String {
    return this.getString(R.string.app_name) + " - SDK v${CustomerIO.instance().sdkVersion}" + " - App v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}

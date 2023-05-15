package io.customer.android.sample.kotlin_compose.util.extensions

import android.content.Context
import io.customer.android.sample.kotlin_compose.BuildConfig
import io.customer.android.sample.kotlin_compose.R
import io.customer.sdk.CustomerIO
import java.util.Locale

fun Context.getUserAgent(): String {
    return String.format(
        Locale.ENGLISH,
        "%s - SDK v%s - App v%s (%s)",
        this.getString(R.string.app_name),
        CustomerIO.instance().sdkVersion,
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE
    )
}

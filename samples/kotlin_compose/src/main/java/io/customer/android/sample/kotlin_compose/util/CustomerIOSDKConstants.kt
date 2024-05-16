package io.customer.android.sample.kotlin_compose.util

import io.customer.datapipelines.extensions.apiHost
import io.customer.datapipelines.extensions.cdnHost
import io.customer.sdk.data.model.Region

object CustomerIOSDKConstants {
    val DEFAULT_API_HOST = Region.US.apiHost()
    val DEFAULT_CDN_HOST = Region.US.cdnHost()
    const val AUTO_TRACK_DEVICE_ATTRIBUTES = true
    const val FLUSH_AT = 20
    const val FLUSH_INTERVAL = 30
    const val AUTO_TRACK_SCREEN_VIEWS = false
}

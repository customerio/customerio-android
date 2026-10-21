package io.customer.sdk.data.model

import io.customer.base.internal.InternalCustomerIOApi

/**
 * How Firebase addresses this device: a legacy FCM registration token or a Firebase Installation ID.
 * [value] is what Customer.io receives.
 */
@InternalCustomerIOApi
enum class DeviceTokenType(val value: String) {
    TOKEN("token"),
    FID("fid")
}

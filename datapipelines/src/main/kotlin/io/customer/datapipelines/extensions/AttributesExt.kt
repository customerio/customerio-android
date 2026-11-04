package io.customer.datapipelines.extensions

import io.customer.sdk.DataPipelinesLogger
import io.customer.sdk.data.model.DeviceTokenType

internal const val DEVICE_TOKEN_TYPE_ATTRIBUTE = "cio_token_type"

/** "1" when the app registered a silent push callback, "0" otherwise. Sent on every device update. */
internal const val BACKGROUND_PUSH_VERSION_ATTRIBUTE = "cio_background_push_version"

/**
 * Sets the [DEVICE_TOKEN_TYPE_ATTRIBUTE] device attribute from [tokenType]. The backend trusts it to pick how it
 * addresses the device, so only the SDK sets it: a value the app passes is dropped.
 */
internal fun Map<String, Any?>.withDeviceTokenType(
    tokenType: DeviceTokenType?,
    logger: DataPipelinesLogger
): Map<String, Any?> {
    if (containsKey(DEVICE_TOKEN_TYPE_ATTRIBUTE)) logger.logReservedDeviceTokenTypeIgnored()
    return if (tokenType == null) this - DEVICE_TOKEN_TYPE_ATTRIBUTE else this + (DEVICE_TOKEN_TYPE_ATTRIBUTE to tokenType.value)
}

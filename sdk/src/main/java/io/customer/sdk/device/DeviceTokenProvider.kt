package io.customer.sdk.device

import android.content.Context

/**
 *  Responsible for token generation and validity
 */
interface DeviceTokenProvider {
    fun isValidForThisDevice(context: Context): Boolean
    fun getCurrentToken(onComplete: (String?) -> Unit)
}

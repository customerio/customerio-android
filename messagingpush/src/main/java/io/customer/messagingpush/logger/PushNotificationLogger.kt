package io.customer.messagingpush.logger

import io.customer.sdk.core.util.Logger

internal class PushNotificationLogger(private val logger: Logger) {

    companion object {
        const val TAG = "Push"
    }

    fun logGooglePlayServicesAvailable() {
        logger.debug(
            tag = TAG,
            message = "Google Play Services is available for this device"
        )
    }

    fun logGooglePlayServicesUnavailable(result: Int) {
        logger.debug(
            tag = TAG,
            message = "Google Play Services is NOT available for this device with result: $result"
        )
    }

    fun logGooglePlayServicesAvailabilityCheckFailed(throwable: Throwable) {
        logger.error(
            tag = TAG,
            message = "Checking Google Play Service availability check failed with error: : ${throwable.message}",
            throwable = throwable
        )
    }
}

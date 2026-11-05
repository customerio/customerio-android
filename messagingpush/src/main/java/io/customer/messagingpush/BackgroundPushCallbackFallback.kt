package io.customer.messagingpush

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messagingpush.data.communication.CustomerIOBackgroundPushCallback

/**
 * Silent push callback for wrapper SDKs such as React Native and Flutter. Firebase can start the
 * app's process to deliver a silent push before the wrapper's JavaScript or Dart code initializes
 * the SDK, so no push module config with a callback exists yet. A wrapper sets [callback] at
 * process start, for example from a ContentProvider. The SDK uses it only when the push module
 * config has no callback, so one set with
 * [MessagingPushModuleConfig.Builder.setBackgroundPushCallback] always wins.
 *
 * Apps should call [MessagingPushModuleConfig.Builder.setBackgroundPushCallback] instead.
 */
@InternalCustomerIOApi
class BackgroundPushCallbackFallback internal constructor() {
    @Volatile
    var callback: CustomerIOBackgroundPushCallback? = null
}

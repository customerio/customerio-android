package io.customer.messagingpush

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messagingpush.data.communication.CustomerIOBackgroundPushCallback

/**
 * Silent push callback that wrapper SDKs such as React Native and Flutter set at process start,
 * for example from a ContentProvider. Firebase can start the process for a silent push before the
 * wrapper's JavaScript or Dart code sets up the SDK. A callback set with
 * [MessagingPushModuleConfig.Builder.setBackgroundPushCallback] wins, and apps should use that instead.
 */
@InternalCustomerIOApi
class BackgroundPushCallbackFallback internal constructor() {
    @Volatile
    var callback: CustomerIOBackgroundPushCallback? = null
}

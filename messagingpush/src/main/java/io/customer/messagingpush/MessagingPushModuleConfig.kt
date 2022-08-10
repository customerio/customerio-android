package io.customer.messagingpush

import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.sdk.module.CustomerIOModuleConfig

/**
 * Push messaging module configurations
 * <p/>
 * Please note for apps targeting Android 12 or greater, all other apps and
 * browser intents will be opened over host app so that notification metrics
 * are not affected
 *
 * @property notificationCallback callback to override default sdk behaviour for
 * notifications
 * @property redirectDeepLinksToOtherApps flag to support opening urls from
 * notification to other native apps or browsers; default true
 */
class MessagingPushModuleConfig @JvmOverloads constructor(
    val notificationCallback: CustomerIOPushNotificationCallback? = null,
    val redirectDeepLinksToOtherApps: Boolean = true
) : CustomerIOModuleConfig

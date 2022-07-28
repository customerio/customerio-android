package io.customer.messagingpush

import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.sdk.module.CustomerIOModuleConfig

/**
 * Push messaging module configurations
 * <p/>
 * Please note that all other apps and browser intents will be opened over
 * host app so that notification metrics are not affected
 *
 * @property notificationCallback callback to override default sdk behaviour for
 * notifications
 * @property redirectDeepLinksToThirdPartyApps flag to support opening third party urls in
 * notification in other apps; default true
 * @property redirectDeepLinksToHttpBrowser flag to support opening unknown urls from
 * notifications in browser; default true
 */
class MessagingPushModuleConfig(
    val notificationCallback: CustomerIOPushNotificationCallback? = null,
    val redirectDeepLinksToThirdPartyApps: Boolean = true,
    val redirectDeepLinksToHttpBrowser: Boolean = true
) : CustomerIOModuleConfig

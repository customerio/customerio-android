package io.customer.messagingpush

import io.customer.messagingpush.config.PushClickBehavior
import io.customer.messagingpush.config.PushClickBehavior.ACTIVITY_PREVENT_RESTART
import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.sdk.core.module.CustomerIOModuleConfig

/**
 * Push messaging module configurations
 *
 * @property notificationCallback callback to override default sdk behaviour for
 * notifications
 * @property redirectDeepLinksToOtherApps flag to support opening urls from
 * notification to other native apps or browsers; default true
 * @property pushClickBehavior defines the behavior when a push notification
 * is clicked
 */
class MessagingPushModuleConfig private constructor(
    val autoTrackPushEvents: Boolean,
    val notificationCallback: CustomerIOPushNotificationCallback?,
    val redirectDeepLinksToOtherApps: Boolean,
    val pushClickBehavior: PushClickBehavior
) : CustomerIOModuleConfig {
    class Builder : CustomerIOModuleConfig.Builder<MessagingPushModuleConfig> {
        private var autoTrackPushEvents: Boolean = true
        private var notificationCallback: CustomerIOPushNotificationCallback? = null
        private var redirectDeepLinksToOtherApps: Boolean = true
        private var pushClickBehavior: PushClickBehavior = ACTIVITY_PREVENT_RESTART

        /**
         * Allows to enable/disable automatic tracking of push events. Auto tracking will generate
         * opened and delivered metrics for push notifications sent by Customer.io without
         * any additional code
         *
         * @param autoTrackPushEvents true to enable auto tracking, false otherwise; default true
         */
        fun setAutoTrackPushEvents(autoTrackPushEvents: Boolean): Builder {
            this.autoTrackPushEvents = autoTrackPushEvents
            return this
        }

        /**
         * Callback that notifies client on push notification related actions.
         *
         * @param notificationCallback listener to receive callback events
         */
        fun setNotificationCallback(notificationCallback: CustomerIOPushNotificationCallback): Builder {
            this.notificationCallback = notificationCallback
            return this
        }

        /**
         * Allows to enable/disable opening non app links outside the app
         * <p>
         * true: links not supported by apps will be opened in other matching apps,
         * if no matching apps are found, host app will be launched to default landing page
         * false: links not supported will only open the host app to its default landing page
         *
         * @param redirectDeepLinksToOtherApps flag to support opening urls from
         * notification to other native apps or browsers; default true
         */
        fun setRedirectDeepLinksToOtherApps(redirectDeepLinksToOtherApps: Boolean): Builder {
            this.redirectDeepLinksToOtherApps = redirectDeepLinksToOtherApps
            return this
        }

        /**
         * Defines the behavior when a notification is clicked.
         *
         * @param pushClickBehavior the behavior when a notification is clicked; default [PushClickBehavior.ACTIVITY_PREVENT_RESTART].
         * @see PushClickBehavior for more details.
         */
        fun setPushClickBehavior(pushClickBehavior: PushClickBehavior): Builder {
            this.pushClickBehavior = pushClickBehavior
            return this
        }

        override fun build(): MessagingPushModuleConfig {
            return MessagingPushModuleConfig(
                autoTrackPushEvents = autoTrackPushEvents,
                notificationCallback = notificationCallback,
                redirectDeepLinksToOtherApps = redirectDeepLinksToOtherApps,
                pushClickBehavior = pushClickBehavior
            )
        }
    }

    companion object {
        internal fun default(): MessagingPushModuleConfig = Builder().build()
    }
}

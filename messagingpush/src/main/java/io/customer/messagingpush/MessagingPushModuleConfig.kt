package io.customer.messagingpush

import io.customer.messagingpush.config.NotificationClickBehavior
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
 * @property notificationOnClickBehavior defines the behavior when a notification
 * is clicked
 */
class MessagingPushModuleConfig private constructor(
    val autoTrackPushEvents: Boolean,
    val notificationCallback: CustomerIOPushNotificationCallback?,
    val redirectDeepLinksToOtherApps: Boolean,
    val notificationOnClickBehavior: NotificationClickBehavior
) : CustomerIOModuleConfig {
    class Builder : CustomerIOModuleConfig.Builder<MessagingPushModuleConfig> {
        private var autoTrackPushEvents: Boolean = true
        private var notificationCallback: CustomerIOPushNotificationCallback? = null
        private var redirectDeepLinksToOtherApps: Boolean = true
        private var notificationOnClickBehavior: NotificationClickBehavior =
            NotificationClickBehavior.TASK_RESET_ALWAYS

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
         * @param notificationOnClickBehavior the behavior when a notification is clicked; default [NotificationClickBehavior.TASK_RESET_ALWAYS].
         * @see NotificationClickBehavior for more details.
         */
        fun setNotificationClickBehavior(notificationOnClickBehavior: NotificationClickBehavior): Builder {
            this.notificationOnClickBehavior = notificationOnClickBehavior
            return this
        }

        override fun build(): MessagingPushModuleConfig {
            return MessagingPushModuleConfig(
                autoTrackPushEvents = autoTrackPushEvents,
                notificationCallback = notificationCallback,
                redirectDeepLinksToOtherApps = redirectDeepLinksToOtherApps,
                notificationOnClickBehavior = notificationOnClickBehavior
            )
        }
    }

    companion object {
        internal fun default(): MessagingPushModuleConfig = Builder().build()
    }
}

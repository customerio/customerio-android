package io.customer.messagingpush

import io.customer.messagingpush.config.PushClickBehavior
import io.customer.messagingpush.config.PushClickBehavior.ACTIVITY_PREVENT_RESTART
import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import io.customer.sdk.core.module.CustomerIOModuleConfig

/**
 * Push messaging module configurations
 *
 * @property notificationCallback callback to override default sdk behaviour for
 * notifications
 * @property pushClickBehavior defines the behavior when a push notification
 * is clicked
 * @property liveNotificationBranding app-level branding applied to templated
 * live notifications. `null` means templates fall back to FCM metadata values.
 */
class MessagingPushModuleConfig private constructor(
    val autoTrackPushEvents: Boolean,
    val notificationCallback: CustomerIOPushNotificationCallback?,
    val pushClickBehavior: PushClickBehavior,
    val liveNotificationBranding: LiveNotificationBranding?,
    val liveNotificationTypes: Set<String>
) : CustomerIOModuleConfig {
    class Builder : CustomerIOModuleConfig.Builder<MessagingPushModuleConfig> {
        private var autoTrackPushEvents: Boolean = true
        private var notificationCallback: CustomerIOPushNotificationCallback? = null
        private var pushClickBehavior: PushClickBehavior = ACTIVITY_PREVENT_RESTART
        private var liveNotificationBranding: LiveNotificationBranding? = null
        private var liveNotificationTypes: Set<String> = emptySet()

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
         * Defines the behavior when a notification is clicked.
         *
         * @param pushClickBehavior the behavior when a notification is clicked; default [PushClickBehavior.ACTIVITY_PREVENT_RESTART].
         * @see PushClickBehavior for more details.
         */
        fun setPushClickBehavior(pushClickBehavior: PushClickBehavior): Builder {
            this.pushClickBehavior = pushClickBehavior
            return this
        }

        /**
         * Sets the app-level branding applied to templated live notifications.
         *
         * @param liveNotificationBranding branding bundle (company name, accent color, logo).
         */
        fun setLiveNotificationBranding(liveNotificationBranding: LiveNotificationBranding): Builder {
            this.liveNotificationBranding = liveNotificationBranding
            return this
        }

        /**
         * Enables live notifications for the given activity types. **This is
         * required to use live notifications** — until at least one type is
         * enabled the feature is a no-op (nothing is registered with Customer.io
         * and pushes for non-enabled types are ignored).
         *
         * Pass built-in types from [io.customer.messagingpush.livenotification.LiveNotificationType]
         * (rendered by the SDK's templates) and/or your own custom type strings
         * (rendered by [CustomerIOPushNotificationCallback.createLiveNotification],
         * which must be provided for custom types).
         *
         * @param types reverse-DNS activity type identifiers to enable.
         */
        fun setLiveNotificationTypes(vararg types: String): Builder {
            this.liveNotificationTypes = types.toSet()
            return this
        }

        override fun build(): MessagingPushModuleConfig {
            return MessagingPushModuleConfig(
                autoTrackPushEvents = autoTrackPushEvents,
                notificationCallback = notificationCallback,
                pushClickBehavior = pushClickBehavior,
                liveNotificationBranding = liveNotificationBranding,
                liveNotificationTypes = liveNotificationTypes
            )
        }
    }

    override fun toString(): String {
        return "MessagingPushModuleConfig(autoTrackPushEvents=$autoTrackPushEvents, notificationCallback=$notificationCallback, pushClickBehavior=$pushClickBehavior, liveNotificationBranding=$liveNotificationBranding, liveNotificationTypes=$liveNotificationTypes)"
    }

    companion object {
        internal fun default(): MessagingPushModuleConfig = Builder().build()
    }
}

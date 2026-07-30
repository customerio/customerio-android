package io.customer.messagingpush

import io.customer.messagingpush.config.PushClickBehavior
import io.customer.messagingpush.config.PushClickBehavior.ACTIVITY_PREVENT_RESTART
import io.customer.messagingpush.data.communication.CustomerIOLiveNotificationsCallback
import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import io.customer.messagingpush.livenotification.LiveNotificationType
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
 * @property liveNotificationTypes activity type identifiers (built-in + custom)
 * the host app enabled.
 * @property liveNotificationCallback lets the host app render live notifications
 * itself. `null` means the SDK renders its built-in templates.
 */
class MessagingPushModuleConfig private constructor(
    val autoTrackPushEvents: Boolean,
    val notificationCallback: CustomerIOPushNotificationCallback?,
    val pushClickBehavior: PushClickBehavior,
    val liveNotificationBranding: LiveNotificationBranding?,
    val liveNotificationTypes: Set<String>,
    val liveNotificationCallback: CustomerIOLiveNotificationsCallback?
) : CustomerIOModuleConfig {
    class Builder : CustomerIOModuleConfig.Builder<MessagingPushModuleConfig> {
        private var autoTrackPushEvents: Boolean = true
        private var notificationCallback: CustomerIOPushNotificationCallback? = null
        private var pushClickBehavior: PushClickBehavior = ACTIVITY_PREVENT_RESTART
        private var liveNotificationBranding: LiveNotificationBranding? = null
        private val liveNotificationTypes: MutableSet<String> = mutableSetOf()
        private var liveNotificationCallback: CustomerIOLiveNotificationsCallback? = null

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
         * Enables live notifications for the given built-in [types] (rendered by
         * the SDK's templates). At least one type — built-in or custom — must be
         * enabled or the feature is a no-op. Repeatable and additive with
         * [enableCustomLiveNotificationTypes].
         *
         * For the promoted "live update" treatment on Android 16+ (a notification
         * pinned to the status bar / lock screen), the host app must declare
         * `android.permission.POST_PROMOTED_NOTIFICATIONS` in its manifest — the
         * SDK deliberately does not declare it on the app's behalf. Without it,
         * live notifications still post as standard ongoing notifications.
         *
         * @param types built-in activity types to enable.
         */
        fun enableLiveNotificationTypes(vararg types: LiveNotificationType): Builder {
            types.forEach { liveNotificationTypes.add(it.identifier) }
            return this
        }

        /**
         * Enables live notifications for customer-defined [types] (reverse-DNS
         * identifier strings). Custom types have no built-in template, so a
         * [CustomerIOLiveNotificationsCallback] set via
         * [setLiveNotificationCallback] must render them.
         *
         * Repeatable and additive with [enableLiveNotificationTypes].
         *
         * @param types custom activity type identifiers to enable.
         */
        fun enableCustomLiveNotificationTypes(vararg types: String): Builder {
            liveNotificationTypes.addAll(types)
            return this
        }

        /**
         * Lets the host app render live notifications itself instead of using the
         * SDK's built-in templates. Required for custom activity types enabled via
         * [enableCustomLiveNotificationTypes], which have no built-in template.
         *
         * @param liveNotificationCallback renderer invoked for every live notification.
         */
        fun setLiveNotificationCallback(liveNotificationCallback: CustomerIOLiveNotificationsCallback): Builder {
            this.liveNotificationCallback = liveNotificationCallback
            return this
        }

        override fun build(): MessagingPushModuleConfig {
            return MessagingPushModuleConfig(
                autoTrackPushEvents = autoTrackPushEvents,
                notificationCallback = notificationCallback,
                pushClickBehavior = pushClickBehavior,
                liveNotificationBranding = liveNotificationBranding,
                liveNotificationTypes = liveNotificationTypes.toSet(),
                liveNotificationCallback = liveNotificationCallback
            )
        }
    }

    override fun toString(): String {
        return "MessagingPushModuleConfig(autoTrackPushEvents=$autoTrackPushEvents, notificationCallback=$notificationCallback, pushClickBehavior=$pushClickBehavior, liveNotificationBranding=$liveNotificationBranding, liveNotificationTypes=$liveNotificationTypes, liveNotificationCallback=$liveNotificationCallback)"
    }

    companion object {
        internal fun default(): MessagingPushModuleConfig = Builder().build()
    }
}

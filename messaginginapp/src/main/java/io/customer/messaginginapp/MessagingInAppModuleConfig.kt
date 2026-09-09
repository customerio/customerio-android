package io.customer.messaginginapp

import io.customer.messaginginapp.type.ColorScheme
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.messaginginapp.type.InboxEventListener
import io.customer.messaginginapp.type.NotificationInboxAccessibilityLabels
import io.customer.sdk.core.module.CustomerIOModuleConfig
import io.customer.sdk.data.model.Region

/**
 * In app messaging module configurations that can be used to customize app
 * experience based on the provided configurations
 */
class MessagingInAppModuleConfig private constructor(
    val siteId: String,
    val region: Region,
    val eventListener: InAppEventListener?,
    val colorScheme: ColorScheme,
    val inboxEventListener: InboxEventListener?,
    /**
     * Host-provided TalkBack labels for the Visual Notification Inbox UI. All null by default (the SDK
     * emits no strings of its own). See [NotificationInboxAccessibilityLabels].
     */
    val notificationInboxAccessibilityLabels: NotificationInboxAccessibilityLabels
) : CustomerIOModuleConfig {
    class Builder(
        private val siteId: String,
        private val region: Region
    ) : CustomerIOModuleConfig.Builder<MessagingInAppModuleConfig> {
        private var eventListener: InAppEventListener? = null
        private var colorScheme: ColorScheme = ColorScheme.AUTO
        private var inboxEventListener: InboxEventListener? = null
        private var notificationInboxAccessibilityLabels: NotificationInboxAccessibilityLabels = NotificationInboxAccessibilityLabels()

        fun setEventListener(eventListener: InAppEventListener): Builder {
            this.eventListener = eventListener
            return this
        }

        fun setColorScheme(colorScheme: ColorScheme): Builder {
            this.colorScheme = colorScheme
            return this
        }

        /**
         * Registers a listener notified when an action is taken on a visual notification inbox
         * message. The listener can intercept actions (return `true`) to override the SDK's default
         * navigation. Mirrors [setEventListener] for inbox actions.
         */
        fun setInboxEventListener(inboxEventListener: InboxEventListener): Builder {
            this.inboxEventListener = inboxEventListener
            return this
        }

        /**
         * Sets the TalkBack labels for the Visual Notification Inbox UI (bell, unread badge, loading
         * and empty states). The SDK ships no default labels, so provide these in your app's language
         * if you want the inbox to be announced by assistive technologies. See
         * [NotificationInboxAccessibilityLabels].
         */
        fun setNotificationInboxAccessibilityLabels(labels: NotificationInboxAccessibilityLabels): Builder {
            this.notificationInboxAccessibilityLabels = labels
            return this
        }

        override fun build(): MessagingInAppModuleConfig {
            return MessagingInAppModuleConfig(
                siteId = siteId,
                region = region,
                eventListener = eventListener,
                colorScheme = colorScheme,
                inboxEventListener = inboxEventListener,
                notificationInboxAccessibilityLabels = notificationInboxAccessibilityLabels
            )
        }
    }
}

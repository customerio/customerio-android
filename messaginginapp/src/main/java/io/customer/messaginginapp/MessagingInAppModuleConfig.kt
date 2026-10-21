package io.customer.messaginginapp

import io.customer.messaginginapp.type.ColorScheme
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.messaginginapp.type.InboxEventListener
import io.customer.messaginginapp.type.NotificationInboxAccessibilityLabels
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModuleConfig
import io.customer.sdk.core.pipeline.DataPipeline
import io.customer.sdk.core.util.CioApiKey
import io.customer.sdk.data.model.Region

/**
 * In app messaging module configurations that can be used to customize app
 * experience based on the provided configurations
 */
class MessagingInAppModuleConfig private constructor(
    /**
     * Empty when not set, which is fine when the SDK is set up with a public (`pk_`) key.
     */
    val siteId: String,
    @Deprecated("Ignored, in-app uses the region the SDK is set up with")
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
    // Read from the SDK as it was set up in CustomerIO init, so they always match it
    private val dataPipeline: DataPipeline?
        get() = SDKComponent.getOrNull<DataPipeline>()

    /**
     * Public (`pk_`) key the SDK is set up with, which in-app uses instead of the site ID, or null.
     */
    internal val publicKey: String?
        get() = dataPipeline?.cdpApiKey?.takeIf { CioApiKey.isPublicKey(it) }

    internal val dataCenter: String
        get() = (dataPipeline?.region ?: Region.US).code

    /**
     * Use without a site ID when the SDK is set up with a public (`pk_`) key.
     */
    class Builder() : CustomerIOModuleConfig.Builder<MessagingInAppModuleConfig> {
        private var siteId: String? = null

        // Only kept for the deprecated [MessagingInAppModuleConfig.region]
        private var region: Region = Region.US

        constructor(siteId: String) : this() {
            this.siteId = siteId
        }

        @Deprecated(
            "Ignored, in-app uses the region the SDK is set up with",
            ReplaceWith("Builder(siteId)")
        )
        constructor(siteId: String, region: Region) : this(siteId) {
            this.region = region
        }

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
                siteId = siteId.orEmpty(),
                region = region,
                eventListener = eventListener,
                colorScheme = colorScheme,
                inboxEventListener = inboxEventListener,
                notificationInboxAccessibilityLabels = notificationInboxAccessibilityLabels
            )
        }
    }
}

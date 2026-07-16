package io.customer.messagingpush.livenotification

/**
 * Built-in live-notification activity types for the SDK's bundled templates.
 *
 * Pass these to [io.customer.messagingpush.MessagingPushModuleConfig.Builder.enableLiveNotificationTypes].
 * For customer-defined types use `enableCustomLiveNotificationTypes` instead.
 * The feature is a no-op until at least one type is enabled.
 */
enum class LiveNotificationType(val identifier: String) {
    SEGMENTS("io.customer.livenotifications.segments"),
    COUNTDOWN_TIMER("io.customer.livenotifications.countdowntimer")
}

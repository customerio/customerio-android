package io.customer.messagingpush.livenotification

/**
 * Built-in live-notification activity types (reverse-DNS [identifier]s matching
 * the iOS Live Activity identifiers).
 *
 * Pass these to
 * [io.customer.messagingpush.MessagingPushModuleConfig.Builder.enableLiveNotificationTypes]
 * to enable the SDK's built-in templates. For customer-defined types (rendered
 * by [io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback.createLiveNotification])
 * use [io.customer.messagingpush.MessagingPushModuleConfig.Builder.enableCustomLiveNotificationTypes]
 * instead.
 *
 * The feature is a no-op until at least one type is enabled: nothing is
 * registered with the backend and pushes for non-enabled types are ignored.
 */
enum class LiveNotificationType(val identifier: String) {
    DELIVERY_TRACKING("io.customer.liveactivities.deliverytracking"),
    FLIGHT_STATUS("io.customer.liveactivities.flightstatus"),
    LIVE_SCORE("io.customer.liveactivities.livescore"),
    COUNTDOWN_TIMER("io.customer.liveactivities.countdowntimer"),
    AUCTION_BID("io.customer.liveactivities.auctionbid")
}

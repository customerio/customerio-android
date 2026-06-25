package io.customer.messagingpush.livenotification

/**
 * Built-in live-notification activity type identifiers (reverse-DNS, matching
 * the iOS Live Activity identifiers).
 *
 * Pass these — and/or your own custom type strings — to
 * [io.customer.messagingpush.MessagingPushModuleConfig.Builder.enableLiveNotificationTypes]
 * to enable live notifications. The feature is a no-op until at least one type
 * is enabled: nothing is registered with the backend and pushes for
 * non-enabled types are ignored.
 */
object LiveNotificationType {
    const val DELIVERY_TRACKING = "io.customer.liveactivities.deliverytracking"
    const val FLIGHT_STATUS = "io.customer.liveactivities.flightstatus"
    const val LIVE_SCORE = "io.customer.liveactivities.livescore"
    const val COUNTDOWN_TIMER = "io.customer.liveactivities.countdowntimer"
    const val AUCTION_BID = "io.customer.liveactivities.auctionbid"
}

package io.customer.messagingpush.livenotification.template

import io.customer.messagingpush.livenotification.LiveNotificationType

internal object TemplateRegistry {

    // Aliases to the public identifiers (single source of truth: LiveNotificationType).
    const val DELIVERY_TRACKING = LiveNotificationType.DELIVERY_TRACKING
    const val FLIGHT_STATUS = LiveNotificationType.FLIGHT_STATUS
    const val LIVE_SCORE = LiveNotificationType.LIVE_SCORE
    const val COUNTDOWN_TIMER = LiveNotificationType.COUNTDOWN_TIMER
    const val AUCTION_BID = LiveNotificationType.AUCTION_BID

    fun find(name: String?): LiveNotificationTemplate? = when (name) {
        DELIVERY_TRACKING -> DeliveryTrackingTemplate
        FLIGHT_STATUS -> FlightStatusTemplate
        LIVE_SCORE -> LiveScoreTemplate
        COUNTDOWN_TIMER -> CountdownTimerTemplate
        AUCTION_BID -> AuctionBidTemplate
        else -> null
    }
}

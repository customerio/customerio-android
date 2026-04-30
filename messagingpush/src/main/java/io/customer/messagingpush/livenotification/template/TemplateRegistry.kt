package io.customer.messagingpush.livenotification.template

internal object TemplateRegistry {

    const val DELIVERY_TRACKING = "delivery_tracking"
    const val FLIGHT_STATUS = "flight_status"
    const val LIVE_SCORE = "live_score"
    const val COUNTDOWN_TIMER = "countdown_timer"
    const val AUCTION_BID = "auction_bid"

    fun find(name: String?): LiveNotificationTemplate? = when (name) {
        DELIVERY_TRACKING -> DeliveryTrackingTemplate
        FLIGHT_STATUS -> FlightStatusTemplate
        LIVE_SCORE -> LiveScoreTemplate
        COUNTDOWN_TIMER -> CountdownTimerTemplate
        AUCTION_BID -> AuctionBidTemplate
        else -> null
    }
}

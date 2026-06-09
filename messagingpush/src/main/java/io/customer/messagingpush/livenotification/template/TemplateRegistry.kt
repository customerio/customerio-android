package io.customer.messagingpush.livenotification.template

internal object TemplateRegistry {

    const val DELIVERY_TRACKING = "io.customer.live.delivery_tracking"
    const val FLIGHT_STATUS = "io.customer.live.flight_status"
    const val LIVE_SCORE = "io.customer.live.live_score"
    const val COUNTDOWN_TIMER = "io.customer.live.countdown_timer"
    const val AUCTION_BID = "io.customer.live.auction_bid"

    fun find(name: String?): LiveNotificationTemplate? = when (name) {
        DELIVERY_TRACKING -> DeliveryTrackingTemplate
        FLIGHT_STATUS -> FlightStatusTemplate
        LIVE_SCORE -> LiveScoreTemplate
        COUNTDOWN_TIMER -> CountdownTimerTemplate
        AUCTION_BID -> AuctionBidTemplate
        else -> null
    }
}

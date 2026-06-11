package io.customer.messagingpush.livenotification.template

internal object TemplateRegistry {

    const val DELIVERY_TRACKING = "io.customer.liveactivities.deliverytracking"
    const val FLIGHT_STATUS = "io.customer.liveactivities.flightstatus"
    const val LIVE_SCORE = "io.customer.liveactivities.livescore"
    const val COUNTDOWN_TIMER = "io.customer.liveactivities.countdowntimer"
    const val AUCTION_BID = "io.customer.liveactivities.auctionbid"

    /** The built-in activity types the SDK registers for and can render. */
    val builtInTypes: List<String> = listOf(
        DELIVERY_TRACKING,
        FLIGHT_STATUS,
        LIVE_SCORE,
        COUNTDOWN_TIMER,
        AUCTION_BID
    )

    fun find(name: String?): LiveNotificationTemplate? = when (name) {
        DELIVERY_TRACKING -> DeliveryTrackingTemplate
        FLIGHT_STATUS -> FlightStatusTemplate
        LIVE_SCORE -> LiveScoreTemplate
        COUNTDOWN_TIMER -> CountdownTimerTemplate
        AUCTION_BID -> AuctionBidTemplate
        else -> null
    }
}

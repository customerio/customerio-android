package io.customer.messagingpush.livenotification.template

/**
 * Single source of truth for live-notification field names. Referenced by both
 * the push-render path (each `*Template.render` reading the flattened payload)
 * and the local-start path (`LiveNotificationData.fields()`), so the two can't
 * drift apart.
 */
internal object DeliveryTrackingFields {
    const val ORDER_ID = "orderId"
    const val RECIPIENT_NAME = "recipientName"
    const val STATUS_MESSAGE = "statusMessage"
    const val STATUS_IMAGE_KEY = "statusImageKey"
    const val STEP_CURRENT = "stepCurrent"
    const val STEP_TOTAL = "stepTotal"
    const val ESTIMATED_ARRIVAL = "estimatedArrival"
    const val DRIVER_NAME = "driverName"
}

internal object FlightStatusFields {
    const val FLIGHT_NUMBER = "flightNumber"
    const val ORIGIN = "origin"
    const val DESTINATION = "destination"
    const val STATUS_MESSAGE = "statusMessage"
    const val GATE = "gate"
    const val TERMINAL = "terminal"
    const val SCHEDULED_DEPARTURE = "scheduledDeparture"
    const val ESTIMATED_ARRIVAL = "estimatedArrival"
    const val PROGRESS_FRACTION = "progressFraction"
    const val DELAY_MINUTES = "delayMinutes"
}

internal object LiveScoreFields {
    const val HOME_TEAM = "homeTeam"
    const val AWAY_TEAM = "awayTeam"
    const val HOME_SCORE = "homeScore"
    const val AWAY_SCORE = "awayScore"
    const val PERIOD = "period"
    const val CLOCK = "clock"
    const val STATUS_MESSAGE = "statusMessage"
    const val SPORT = "sport"
    const val LEAGUE_LOGO_KEY = "leagueLogoKey"
}

internal object CountdownTimerFields {
    const val TITLE = "title"
    const val HERO_IMAGE_KEY = "heroImageKey"
    const val TARGET_DATE = "targetDate"
    const val STATUS_MESSAGE = "statusMessage"
    const val EXPIRED_MESSAGE = "expiredMessage"
}

internal object AuctionBidFields {
    const val ITEM_TITLE = "itemTitle"
    const val ITEM_IMAGE_KEY = "itemImageKey"
    const val CURRENCY_SYMBOL = "currencySymbol"
    const val CURRENT_BID = "currentBid"
    const val BID_COUNT = "bidCount"
    const val END_TIME = "endTime"
    const val STATUS_MESSAGE = "statusMessage"
    const val IS_USER_HIGH_BIDDER = "isUserHighBidder"
    const val USER_BID_AMOUNT = "userBidAmount"
}

/** Nested object sub-fields shared by templates that embed them. */
internal object AirportFields {
    const val CODE = "code"
    const val CITY = "city"
}

internal object TeamFields {
    const val NAME = "name"
    const val LOGO_KEY = "logoKey"
}

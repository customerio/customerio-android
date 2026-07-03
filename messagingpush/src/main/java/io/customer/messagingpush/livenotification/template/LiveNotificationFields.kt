package io.customer.messagingpush.livenotification.template

/**
 * Single source of truth for live-notification field names. Referenced by both
 * the push-render path (each `*Template.render` reading the flattened payload)
 * and the local-start path (`LiveNotificationData.fields()`), so the two can't
 * drift apart.
 *
 * Naming follows the finalized cross-platform field contract: freeform text
 * slots (`header`/`title`/`subtitle`/`status`) the SDK renders verbatim (never
 * composes), typed fields only where rendering needs structure (images, live
 * timers, progress, scores, color). Asset refs drop the `Key` suffix
 * (`image`/`logo`); the value may be a bundle name, asset key, or URL.
 * `statusColor` is a hex string (e.g. `#36AE3F`) parsed to an `@ColorInt`.
 */

/** Freeform slots shared by every template that carries them. */
internal object CommonFields {
    const val HEADER = "header"
    const val TITLE = "title"
    const val SUBTITLE = "subtitle"
    const val STATUS_COLOR = "statusColor"
    const val STALE_MESSAGE = "staleMessage"
    const val IMAGE = "image"
}

internal object DeliveryTrackingFields {
    const val HEADER = CommonFields.HEADER
    const val TITLE = CommonFields.TITLE
    const val SUBTITLE = CommonFields.SUBTITLE
    const val IMAGE = CommonFields.IMAGE
    const val STEP_CURRENT = "stepCurrent"
    const val STEP_TOTAL = "stepTotal"
    const val ESTIMATED_ARRIVAL = "estimatedArrival"
    const val STATUS_COLOR = CommonFields.STATUS_COLOR
    const val STALE_MESSAGE = CommonFields.STALE_MESSAGE
}

internal object FlightStatusFields {
    const val HEADER = CommonFields.HEADER
    const val STATUS = "status"
    const val TITLE = CommonFields.TITLE
    const val SUBTITLE = CommonFields.SUBTITLE
    const val ORIGIN = "origin"
    const val DESTINATION = "destination"
    const val SCHEDULED_DEPARTURE = "scheduledDeparture"
    const val ESTIMATED_ARRIVAL = "estimatedArrival"
    const val PROGRESS_FRACTION = "progressFraction"
    const val STATUS_COLOR = CommonFields.STATUS_COLOR
    const val STALE_MESSAGE = CommonFields.STALE_MESSAGE
}

internal object LiveScoreFields {
    const val HOME_TEAM = "homeTeam"
    const val AWAY_TEAM = "awayTeam"
    const val HOME_SCORE = "homeScore"
    const val AWAY_SCORE = "awayScore"
    const val SUBTITLE = CommonFields.SUBTITLE
    const val IMAGE = CommonFields.IMAGE
    const val STATUS_COLOR = CommonFields.STATUS_COLOR
    const val STALE_MESSAGE = CommonFields.STALE_MESSAGE
}

internal object CountdownTimerFields {
    const val HEADER = CommonFields.HEADER
    const val TITLE = CommonFields.TITLE
    const val SUBTITLE = CommonFields.SUBTITLE
    const val IMAGE = CommonFields.IMAGE
    const val TARGET_DATE = "targetDate"
    const val EXPIRED_MESSAGE = "expiredMessage"
    const val STATUS_COLOR = CommonFields.STATUS_COLOR
    const val STALE_MESSAGE = CommonFields.STALE_MESSAGE
}

internal object AuctionBidFields {
    const val HEADER = CommonFields.HEADER
    const val TITLE = CommonFields.TITLE
    const val SUBTITLE = CommonFields.SUBTITLE
    const val STATUS_MESSAGE = "statusMessage"
    const val IMAGE = CommonFields.IMAGE
    const val CURRENCY_SYMBOL = "currencySymbol"
    const val CURRENT_BID = "currentBid"
    const val END_TIME = "endTime"
    const val STATUS_COLOR = CommonFields.STATUS_COLOR
    const val STALE_MESSAGE = CommonFields.STALE_MESSAGE
}

/** Nested object sub-fields shared by templates that embed them. */
internal object AirportFields {
    const val CODE = "code"
    const val CITY = "city"
}

internal object TeamFields {
    const val NAME = "name"
    const val LOGO = "logo"
}

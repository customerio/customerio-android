package io.customer.messagingpush.livenotification

import io.customer.messagingpush.livenotification.template.AirportFields
import io.customer.messagingpush.livenotification.template.AuctionBidFields
import io.customer.messagingpush.livenotification.template.CountdownTimerFields
import io.customer.messagingpush.livenotification.template.DeliveryTrackingFields
import io.customer.messagingpush.livenotification.template.FlightStatusFields
import io.customer.messagingpush.livenotification.template.LiveScoreFields
import io.customer.messagingpush.livenotification.template.TeamFields
import org.json.JSONObject

/**
 * Typed payload for starting a built-in live notification locally via
 * `ModuleMessagingPushFCM.startLiveNotification`. Each subtype knows its
 * [activityType] and flattens itself into the envelope fields the templates
 * read (the same flattened shape the backend delivers). Field names come from
 * the shared `*Fields` constants so local-start and push-render stay in sync.
 *
 * For customer-defined activity types, use the `Map` overload of
 * `startLiveNotification` instead.
 */
sealed interface LiveNotificationData {
    val activityType: String

    /** Flattened template fields; null values are omitted by the caller. */
    fun fields(): Map<String, Any?>

    data class DeliveryTracking(
        val orderId: String,
        val statusMessage: String,
        val recipientName: String? = null,
        val driverName: String? = null,
        val statusImageKey: String? = null,
        val stepCurrent: Int? = null,
        val stepTotal: Int? = null,
        val estimatedArrival: Long? = null
    ) : LiveNotificationData {
        override val activityType = LiveNotificationType.DELIVERY_TRACKING
        override fun fields() = mapOf(
            DeliveryTrackingFields.ORDER_ID to orderId,
            DeliveryTrackingFields.STATUS_MESSAGE to statusMessage,
            DeliveryTrackingFields.RECIPIENT_NAME to recipientName,
            DeliveryTrackingFields.DRIVER_NAME to driverName,
            DeliveryTrackingFields.STATUS_IMAGE_KEY to statusImageKey,
            DeliveryTrackingFields.STEP_CURRENT to stepCurrent,
            DeliveryTrackingFields.STEP_TOTAL to stepTotal,
            DeliveryTrackingFields.ESTIMATED_ARRIVAL to estimatedArrival
        )
    }

    data class FlightStatus(
        val flightNumber: String,
        val origin: Airport,
        val destination: Airport,
        val statusMessage: String,
        val gate: String? = null,
        val terminal: String? = null,
        val scheduledDeparture: Long? = null,
        val estimatedArrival: Long? = null,
        val progressFraction: Double? = null,
        val delayMinutes: Int? = null
    ) : LiveNotificationData {
        override val activityType = LiveNotificationType.FLIGHT_STATUS
        override fun fields() = mapOf(
            FlightStatusFields.FLIGHT_NUMBER to flightNumber,
            FlightStatusFields.ORIGIN to origin.toJson(),
            FlightStatusFields.DESTINATION to destination.toJson(),
            FlightStatusFields.STATUS_MESSAGE to statusMessage,
            FlightStatusFields.GATE to gate,
            FlightStatusFields.TERMINAL to terminal,
            FlightStatusFields.SCHEDULED_DEPARTURE to scheduledDeparture,
            FlightStatusFields.ESTIMATED_ARRIVAL to estimatedArrival,
            FlightStatusFields.PROGRESS_FRACTION to progressFraction,
            FlightStatusFields.DELAY_MINUTES to delayMinutes
        )
    }

    data class LiveScore(
        val homeTeam: Team,
        val awayTeam: Team,
        val period: String,
        val homeScore: Int = 0,
        val awayScore: Int = 0,
        val clock: String? = null,
        val statusMessage: String? = null,
        val sport: String? = null,
        val leagueLogoKey: String? = null
    ) : LiveNotificationData {
        override val activityType = LiveNotificationType.LIVE_SCORE
        override fun fields() = mapOf(
            LiveScoreFields.HOME_TEAM to homeTeam.toJson(),
            LiveScoreFields.AWAY_TEAM to awayTeam.toJson(),
            LiveScoreFields.PERIOD to period,
            LiveScoreFields.HOME_SCORE to homeScore,
            LiveScoreFields.AWAY_SCORE to awayScore,
            LiveScoreFields.CLOCK to clock,
            LiveScoreFields.STATUS_MESSAGE to statusMessage,
            LiveScoreFields.SPORT to sport,
            LiveScoreFields.LEAGUE_LOGO_KEY to leagueLogoKey
        )
    }

    data class CountdownTimer(
        val title: String,
        val targetDate: Long,
        val statusMessage: String,
        val expiredMessage: String? = null,
        val heroImageKey: String? = null
    ) : LiveNotificationData {
        override val activityType = LiveNotificationType.COUNTDOWN_TIMER
        override fun fields() = mapOf(
            CountdownTimerFields.TITLE to title,
            CountdownTimerFields.TARGET_DATE to targetDate,
            CountdownTimerFields.STATUS_MESSAGE to statusMessage,
            CountdownTimerFields.EXPIRED_MESSAGE to expiredMessage,
            CountdownTimerFields.HERO_IMAGE_KEY to heroImageKey
        )
    }

    data class AuctionBid(
        val itemTitle: String,
        val currentBid: String,
        val bidCount: Int,
        val statusMessage: String,
        val isUserHighBidder: Boolean,
        val endTime: Long? = null,
        val userBidAmount: String? = null,
        val itemImageKey: String? = null,
        val currencySymbol: String? = null
    ) : LiveNotificationData {
        override val activityType = LiveNotificationType.AUCTION_BID
        override fun fields() = mapOf(
            AuctionBidFields.ITEM_TITLE to itemTitle,
            AuctionBidFields.CURRENT_BID to currentBid,
            AuctionBidFields.BID_COUNT to bidCount,
            AuctionBidFields.STATUS_MESSAGE to statusMessage,
            AuctionBidFields.IS_USER_HIGH_BIDDER to isUserHighBidder,
            AuctionBidFields.END_TIME to endTime,
            AuctionBidFields.USER_BID_AMOUNT to userBidAmount,
            AuctionBidFields.ITEM_IMAGE_KEY to itemImageKey,
            AuctionBidFields.CURRENCY_SYMBOL to currencySymbol
        )
    }

    /** Airport endpoint for [FlightStatus]. */
    data class Airport(val code: String, val city: String? = null) {
        internal fun toJson(): JSONObject = JSONObject().put(AirportFields.CODE, code).apply {
            city?.let { put(AirportFields.CITY, it) }
        }
    }

    /** Team for [LiveScore]. */
    data class Team(val name: String, val logoKey: String? = null) {
        internal fun toJson(): JSONObject = JSONObject().put(TeamFields.NAME, name).apply {
            logoKey?.let { put(TeamFields.LOGO_KEY, it) }
        }
    }
}

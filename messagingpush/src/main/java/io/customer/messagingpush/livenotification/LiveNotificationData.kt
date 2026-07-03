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
 * [activityType] and exposes its fields three ways:
 *  - [fields] — the flat map the local device-render path reads (the same
 *    flattened shape the backend delivers to the templates);
 *  - [attributes] — the STATIC subset (iOS `attributes`); and
 *  - [contentState] — the DYNAMIC subset (iOS `contentState`).
 *
 * The static/dynamic split mirrors the finalized cross-platform field contract
 * so the CDP lifecycle event can carry `attributes` + `contentState` separately
 * (matching iOS's ActivityKit envelope) instead of a single merged payload.
 * Field names come from the shared `*Fields` constants so local-start and
 * push-render stay in sync.
 *
 * For customer-defined activity types, use the `Map` overload of
 * `startLiveNotification` instead.
 */
sealed interface LiveNotificationData {
    val activityType: String

    /** Flattened template fields; null values are omitted by the caller. */
    fun fields(): Map<String, Any?>

    /** Static fields (iOS `attributes`); null values are omitted by the caller. */
    fun attributes(): Map<String, Any?>

    /** Dynamic fields (iOS `contentState`); null values are omitted by the caller. */
    fun contentState(): Map<String, Any?>

    data class DeliveryTracking(
        val title: String,
        val header: String? = null,
        val subtitle: String? = null,
        val image: String? = null,
        val stepCurrent: Int? = null,
        val stepTotal: Int? = null,
        val estimatedArrival: Long? = null,
        val statusColor: String? = null,
        val staleMessage: String? = null
    ) : LiveNotificationData {
        override val activityType = LiveNotificationType.DELIVERY_TRACKING.identifier

        override fun attributes() = mapOf(
            DeliveryTrackingFields.HEADER to header
        )

        override fun contentState() = mapOf(
            DeliveryTrackingFields.TITLE to title,
            DeliveryTrackingFields.SUBTITLE to subtitle,
            DeliveryTrackingFields.IMAGE to image,
            DeliveryTrackingFields.STEP_CURRENT to stepCurrent,
            DeliveryTrackingFields.STEP_TOTAL to stepTotal,
            DeliveryTrackingFields.ESTIMATED_ARRIVAL to estimatedArrival,
            DeliveryTrackingFields.STATUS_COLOR to statusColor,
            DeliveryTrackingFields.STALE_MESSAGE to staleMessage
        )

        override fun fields() = attributes() + contentState()
    }

    data class FlightStatus(
        val title: String,
        val origin: Airport,
        val destination: Airport,
        val header: String? = null,
        val status: String? = null,
        val subtitle: String? = null,
        val scheduledDeparture: Long? = null,
        val estimatedArrival: Long? = null,
        val progressFraction: Double? = null,
        val statusColor: String? = null,
        val staleMessage: String? = null
    ) : LiveNotificationData {
        override val activityType = LiveNotificationType.FLIGHT_STATUS.identifier

        override fun attributes() = mapOf(
            FlightStatusFields.HEADER to header,
            FlightStatusFields.ORIGIN to origin.toJson(),
            FlightStatusFields.DESTINATION to destination.toJson()
        )

        override fun contentState() = mapOf(
            FlightStatusFields.TITLE to title,
            FlightStatusFields.STATUS to status,
            FlightStatusFields.SUBTITLE to subtitle,
            FlightStatusFields.SCHEDULED_DEPARTURE to scheduledDeparture,
            FlightStatusFields.ESTIMATED_ARRIVAL to estimatedArrival,
            FlightStatusFields.PROGRESS_FRACTION to progressFraction,
            FlightStatusFields.STATUS_COLOR to statusColor,
            FlightStatusFields.STALE_MESSAGE to staleMessage
        )

        override fun fields() = attributes() + contentState()
    }

    data class LiveScore(
        val homeTeam: Team,
        val awayTeam: Team,
        val homeScore: Int? = null,
        val awayScore: Int? = null,
        val subtitle: String? = null,
        val image: String? = null,
        val statusColor: String? = null,
        val staleMessage: String? = null
    ) : LiveNotificationData {
        override val activityType = LiveNotificationType.LIVE_SCORE.identifier

        override fun attributes() = mapOf(
            LiveScoreFields.HOME_TEAM to homeTeam.toJson(),
            LiveScoreFields.AWAY_TEAM to awayTeam.toJson(),
            LiveScoreFields.IMAGE to image
        )

        override fun contentState() = mapOf(
            LiveScoreFields.HOME_SCORE to homeScore,
            LiveScoreFields.AWAY_SCORE to awayScore,
            LiveScoreFields.SUBTITLE to subtitle,
            LiveScoreFields.STATUS_COLOR to statusColor,
            LiveScoreFields.STALE_MESSAGE to staleMessage
        )

        override fun fields() = attributes() + contentState()
    }

    data class CountdownTimer(
        val title: String,
        val targetDate: Long,
        val subtitle: String,
        val header: String? = null,
        val expiredMessage: String? = null,
        val image: String? = null,
        val statusColor: String? = null,
        val staleMessage: String? = null
    ) : LiveNotificationData {
        override val activityType = LiveNotificationType.COUNTDOWN_TIMER.identifier

        override fun attributes() = mapOf(
            CountdownTimerFields.HEADER to header,
            CountdownTimerFields.TITLE to title,
            CountdownTimerFields.IMAGE to image
        )

        override fun contentState() = mapOf(
            CountdownTimerFields.SUBTITLE to subtitle,
            CountdownTimerFields.TARGET_DATE to targetDate,
            CountdownTimerFields.EXPIRED_MESSAGE to expiredMessage,
            CountdownTimerFields.STATUS_COLOR to statusColor,
            CountdownTimerFields.STALE_MESSAGE to staleMessage
        )

        override fun fields() = attributes() + contentState()
    }

    data class AuctionBid(
        val title: String,
        val currentBid: String,
        val statusMessage: String,
        val currencySymbol: String,
        val header: String? = null,
        val subtitle: String? = null,
        val endTime: Long? = null,
        val image: String? = null,
        val statusColor: String? = null,
        val staleMessage: String? = null
    ) : LiveNotificationData {
        override val activityType = LiveNotificationType.AUCTION_BID.identifier

        override fun attributes() = mapOf(
            AuctionBidFields.HEADER to header,
            AuctionBidFields.TITLE to title,
            AuctionBidFields.CURRENCY_SYMBOL to currencySymbol,
            AuctionBidFields.IMAGE to image
        )

        override fun contentState() = mapOf(
            AuctionBidFields.SUBTITLE to subtitle,
            AuctionBidFields.STATUS_MESSAGE to statusMessage,
            AuctionBidFields.CURRENT_BID to currentBid,
            AuctionBidFields.END_TIME to endTime,
            AuctionBidFields.STATUS_COLOR to statusColor,
            AuctionBidFields.STALE_MESSAGE to staleMessage
        )

        override fun fields() = attributes() + contentState()
    }

    /** Airport endpoint for [FlightStatus]. */
    data class Airport(val code: String, val city: String? = null) {
        internal fun toJson(): JSONObject = JSONObject().put(AirportFields.CODE, code).apply {
            city?.let { put(AirportFields.CITY, it) }
        }
    }

    /** Team for [LiveScore]. */
    data class Team(val name: String, val logo: String? = null) {
        internal fun toJson(): JSONObject = JSONObject().put(TeamFields.NAME, name).apply {
            logo?.let { put(TeamFields.LOGO, it) }
        }
    }
}

package io.customer.messagingpush.livenotification

import io.customer.messagingpush.livenotification.template.TemplateRegistry
import org.json.JSONObject

/**
 * Typed payload for starting a built-in live notification locally via
 * `ModuleMessagingPushFCM.startLiveNotification`. Each subtype knows its
 * [activityType] and flattens itself into the envelope fields the templates
 * read (the same flattened shape the backend delivers).
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
        override val activityType = TemplateRegistry.DELIVERY_TRACKING
        override fun fields() = mapOf(
            "orderId" to orderId,
            "statusMessage" to statusMessage,
            "recipientName" to recipientName,
            "driverName" to driverName,
            "statusImageKey" to statusImageKey,
            "stepCurrent" to stepCurrent,
            "stepTotal" to stepTotal,
            "estimatedArrival" to estimatedArrival
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
        override val activityType = TemplateRegistry.FLIGHT_STATUS
        override fun fields() = mapOf(
            "flightNumber" to flightNumber,
            "origin" to origin.toJson(),
            "destination" to destination.toJson(),
            "statusMessage" to statusMessage,
            "gate" to gate,
            "terminal" to terminal,
            "scheduledDeparture" to scheduledDeparture,
            "estimatedArrival" to estimatedArrival,
            "progressFraction" to progressFraction,
            "delayMinutes" to delayMinutes
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
        override val activityType = TemplateRegistry.LIVE_SCORE
        override fun fields() = mapOf(
            "homeTeam" to homeTeam.toJson(),
            "awayTeam" to awayTeam.toJson(),
            "period" to period,
            "homeScore" to homeScore,
            "awayScore" to awayScore,
            "clock" to clock,
            "statusMessage" to statusMessage,
            "sport" to sport,
            "leagueLogoKey" to leagueLogoKey
        )
    }

    data class CountdownTimer(
        val title: String,
        val targetDate: Long,
        val statusMessage: String,
        val expiredMessage: String? = null,
        val heroImageKey: String? = null
    ) : LiveNotificationData {
        override val activityType = TemplateRegistry.COUNTDOWN_TIMER
        override fun fields() = mapOf(
            "title" to title,
            "targetDate" to targetDate,
            "statusMessage" to statusMessage,
            "expiredMessage" to expiredMessage,
            "heroImageKey" to heroImageKey
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
        override val activityType = TemplateRegistry.AUCTION_BID
        override fun fields() = mapOf(
            "itemTitle" to itemTitle,
            "currentBid" to currentBid,
            "bidCount" to bidCount,
            "statusMessage" to statusMessage,
            "isUserHighBidder" to isUserHighBidder,
            "endTime" to endTime,
            "userBidAmount" to userBidAmount,
            "itemImageKey" to itemImageKey,
            "currencySymbol" to currencySymbol
        )
    }

    /** Airport endpoint for [FlightStatus]. */
    data class Airport(val code: String, val city: String? = null) {
        internal fun toJson(): JSONObject = JSONObject().put("code", code).apply {
            city?.let { put("city", it) }
        }
    }

    /** Team for [LiveScore]. */
    data class Team(val name: String, val logoKey: String? = null) {
        internal fun toJson(): JSONObject = JSONObject().put("name", name).apply {
            logoKey?.let { put("logoKey", it) }
        }
    }
}

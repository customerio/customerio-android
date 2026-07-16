package io.customer.messagingpush.livenotification

import io.customer.messagingpush.livenotification.template.CountdownTimerFields
import io.customer.messagingpush.livenotification.template.SegmentsFields

/**
 * Typed payload for starting a built-in live notification locally via
 * `ModuleMessagingPushFCM.startLiveNotification`. Each subtype exposes its
 * [fields], plus the static [attributes] and dynamic [contentState] subsets.
 *
 * Time fields such as `endTime` are **epoch seconds**, not milliseconds.
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

    /**
     * Segments template — a status headline over a discrete, multi-step progress
     * bar (matches iOS `CIOSegmentsAttributes`). [segmentsComplete] is clamped to
     * `0..segmentsTotal` at render time.
     */
    data class Segments(
        val header: String,
        val status: String,
        val substatus: String? = null,
        val segmentsTotal: Int,
        val segmentsComplete: Int,
        val trailingText: String? = null
    ) : LiveNotificationData {
        override val activityType = LiveNotificationType.SEGMENTS.identifier

        override fun attributes() = mapOf(
            SegmentsFields.HEADER to header
        )

        override fun contentState() = mapOf(
            SegmentsFields.STATUS to status,
            SegmentsFields.SUBSTATUS to substatus,
            SegmentsFields.SEGMENTS_TOTAL to segmentsTotal,
            SegmentsFields.SEGMENTS_COMPLETE to segmentsComplete,
            SegmentsFields.TRAILING_TEXT to trailingText
        )

        override fun fields() = attributes() + contentState()
    }

    /**
     * Countdown timer template — a status headline over a live countdown to
     * [endTime] (matches iOS `CIOCountdownTimerAttributes`). [endTime] is epoch
     * seconds; drive the finished state by starting/updating with no [endTime].
     */
    data class CountdownTimer(
        val header: String,
        val title: String,
        val statusMessage: String? = null,
        val endTime: Long? = null
    ) : LiveNotificationData {
        override val activityType = LiveNotificationType.COUNTDOWN_TIMER.identifier

        override fun attributes() = mapOf(
            CountdownTimerFields.HEADER to header
        )

        override fun contentState() = mapOf(
            CountdownTimerFields.TITLE to title,
            CountdownTimerFields.STATUS_MESSAGE to statusMessage,
            CountdownTimerFields.END_TIME to endTime
        )

        override fun fields() = attributes() + contentState()
    }
}

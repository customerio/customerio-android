package io.customer.messagingpush.livenotification.template

/**
 * Single source of truth for live-notification field names. Referenced by both
 * the push-render path (each `*Template.render` reading the flattened payload)
 * and the local-start path (`LiveNotificationData.fields()`), so the two can't
 * drift apart.
 *
 * Naming follows the finalized cross-platform field contract and matches the iOS
 * attribute/content-state property names verbatim (iOS declares no CodingKeys,
 * so the wire key equals the property name). Freeform text slots
 * (`header`/`status`/`substatus`/`title`/`statusMessage`) are rendered verbatim,
 * never composed; `segmentsTotal`/`segmentsComplete` are flat integers and
 * `endTime` is an epoch-seconds timestamp.
 */

/** Freeform slots shared by both templates. */
internal object CommonFields {
    const val HEADER = "header"
}

internal object SegmentsFields {
    const val HEADER = CommonFields.HEADER
    const val STATUS = "status"
    const val SUBSTATUS = "substatus"
    const val SEGMENTS_TOTAL = "segmentsTotal"
    const val SEGMENTS_COMPLETE = "segmentsComplete"
    const val TRAILING_TEXT = "trailingText"
}

internal object CountdownTimerFields {
    const val HEADER = CommonFields.HEADER
    const val TITLE = "title"
    const val STATUS_MESSAGE = "statusMessage"
    const val END_TIME = "endTime"
}

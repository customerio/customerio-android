package io.customer.messagingpush.livenotification.template

/**
 * Single source of truth for live-notification field names, shared by the
 * push-render and local-start paths and matching the iOS property names.
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

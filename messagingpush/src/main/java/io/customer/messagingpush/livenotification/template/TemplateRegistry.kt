package io.customer.messagingpush.livenotification.template

import io.customer.messagingpush.livenotification.LiveNotificationType

internal object TemplateRegistry {

    // Aliases to the public identifiers (single source of truth: LiveNotificationType).
    val SEGMENTS = LiveNotificationType.SEGMENTS.identifier
    val COUNTDOWN_TIMER = LiveNotificationType.COUNTDOWN_TIMER.identifier

    fun find(name: String?): LiveNotificationTemplate? = when (name) {
        SEGMENTS -> SegmentsTemplate
        COUNTDOWN_TIMER -> CountdownTimerTemplate
        else -> null
    }
}

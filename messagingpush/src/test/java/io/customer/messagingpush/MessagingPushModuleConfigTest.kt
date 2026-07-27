package io.customer.messagingpush

import io.customer.commontest.core.JUnit5Test
import io.customer.messagingpush.livenotification.LiveNotificationType
import org.amshove.kluent.internal.assertEquals
import org.amshove.kluent.shouldContainSame
import org.junit.jupiter.api.Test

class MessagingPushModuleConfigTest : JUnit5Test() {

    @Test
    fun test_toString_generatesCorrectRepresentation() {
        val config = MessagingPushModuleConfig.default()

        val actual = config.toString()
        assertEquals("MessagingPushModuleConfig(autoTrackPushEvents=true, notificationCallback=null, pushClickBehavior=ACTIVITY_PREVENT_RESTART, liveNotificationBranding=null, liveNotificationTypes=[], liveNotificationCallback=null)", actual)
    }

    @Test
    fun enableLiveNotificationTypes_mapsBuiltInTypesToIdentifiers() {
        val config = MessagingPushModuleConfig.Builder()
            .enableLiveNotificationTypes(
                LiveNotificationType.SEGMENTS,
                LiveNotificationType.COUNTDOWN_TIMER
            )
            .build()

        config.liveNotificationTypes shouldContainSame setOf(
            LiveNotificationType.SEGMENTS.identifier,
            LiveNotificationType.COUNTDOWN_TIMER.identifier
        )
    }

    @Test
    fun enableTypes_builtInAndCustom_areAdditive() {
        val config = MessagingPushModuleConfig.Builder()
            .enableLiveNotificationTypes(LiveNotificationType.SEGMENTS)
            .enableCustomLiveNotificationTypes("com.acme.ride", "com.acme.workout")
            .build()

        config.liveNotificationTypes shouldContainSame setOf(
            LiveNotificationType.SEGMENTS.identifier,
            "com.acme.ride",
            "com.acme.workout"
        )
    }
}

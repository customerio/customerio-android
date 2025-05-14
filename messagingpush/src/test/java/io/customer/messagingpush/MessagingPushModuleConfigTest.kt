package io.customer.messagingpush

import io.customer.commontest.core.JUnit5Test
import org.amshove.kluent.internal.assertEquals
import org.junit.jupiter.api.Test

class MessagingPushModuleConfigTest : JUnit5Test() {

    @Test
    fun test_toString_generatesCorrectRepresentation() {
        val config = MessagingPushModuleConfig.default()

        val actual = config.toString()
        assertEquals("MessagingPushModuleConfig(autoTrackPushEvents=true, notificationCallback=null, pushClickBehavior=ACTIVITY_PREVENT_RESTART)", actual)
    }
}

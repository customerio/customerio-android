package io.customer.messaginginapp.type

import org.amshove.kluent.shouldBeNull
import org.junit.Test

class NotificationInboxAccessibilityLabelsTest {

    @Test
    fun init_givenNoArguments_expectAllLabelsNull() {
        val labels = NotificationInboxAccessibilityLabels()

        labels.bell.shouldBeNull()
        labels.bellWithUnreadCount.shouldBeNull()
        labels.loadingIndicator.shouldBeNull()
        labels.emptyState.shouldBeNull()
    }
}

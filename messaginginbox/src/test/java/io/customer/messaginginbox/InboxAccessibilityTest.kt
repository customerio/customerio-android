package io.customer.messaginginbox

import io.customer.messaginginapp.type.NotificationInboxAccessibilityLabels
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test

/**
 * Unit tests for [resolveBellAccessibilityLabel]: the bell's TalkBack label is derived only
 * from the host's [NotificationInboxAccessibilityLabels] — never from SDK-owned English — and the unread count is
 * announced only while the badge is shown.
 */
class InboxAccessibilityTest {

    @Test
    fun resolveBellLabel_givenNoLabels_expectNullRegardlessOfCount() {
        val labels = NotificationInboxAccessibilityLabels()

        resolveBellAccessibilityLabel(labels, unopenedCount = 0, showsUnreadCount = false).shouldBeNull()
        resolveBellAccessibilityLabel(labels, unopenedCount = 3, showsUnreadCount = true).shouldBeNull()
    }

    @Test
    fun resolveBellLabel_givenBadgeShown_expectUnreadCountLabelWithCount() {
        val labels = NotificationInboxAccessibilityLabels(
            bell = "Aviseringar",
            bellWithUnreadCount = { count -> "$count olästa" }
        )

        resolveBellAccessibilityLabel(labels, unopenedCount = 3, showsUnreadCount = true) shouldBeEqualTo "3 olästa"
    }

    @Test
    fun resolveBellLabel_givenBadgeHidden_expectPlainBellLabelEvenWithUnread() {
        // Branding `unreadIndicator.showAlert = false` hides the badge; the count must not be announced.
        val labels = NotificationInboxAccessibilityLabels(
            bell = "Aviseringar",
            bellWithUnreadCount = { count -> "$count olästa" }
        )

        resolveBellAccessibilityLabel(labels, unopenedCount = 3, showsUnreadCount = false) shouldBeEqualTo "Aviseringar"
    }

    @Test
    fun resolveBellLabel_givenBadgeShownButNoUnreadCountLabel_expectFallbackToBellLabel() {
        val labels = NotificationInboxAccessibilityLabels(bell = "Aviseringar")

        resolveBellAccessibilityLabel(labels, unopenedCount = 3, showsUnreadCount = true) shouldBeEqualTo "Aviseringar"
    }

    @Test
    fun resolveBellLabel_givenOnlyUnreadCountLabelAndBadgeHidden_expectNull() {
        val labels = NotificationInboxAccessibilityLabels(bellWithUnreadCount = { count -> "$count olästa" })

        resolveBellAccessibilityLabel(labels, unopenedCount = 0, showsUnreadCount = false).shouldBeNull()
    }
}

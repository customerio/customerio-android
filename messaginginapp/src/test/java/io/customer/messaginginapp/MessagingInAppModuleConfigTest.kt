package io.customer.messaginginapp

import io.customer.messaginginapp.type.NotificationInboxAccessibilityLabels
import io.customer.sdk.data.model.Region
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test

class MessagingInAppModuleConfigTest {

    @Test
    fun build_givenNoNotificationInboxAccessibilityLabels_expectAllNull() {
        val config = MessagingInAppModuleConfig.Builder(siteId = "site").build()

        config.notificationInboxAccessibilityLabels.bell.shouldBeNull()
        config.notificationInboxAccessibilityLabels.bellWithUnreadCount.shouldBeNull()
        config.notificationInboxAccessibilityLabels.loadingIndicator.shouldBeNull()
        config.notificationInboxAccessibilityLabels.emptyState.shouldBeNull()
    }

    @Test
    fun build_givenNoSiteId_expectEmptySiteId() {
        val config = MessagingInAppModuleConfig.Builder().build()

        config.siteId shouldBeEqualTo ""
    }

    @Test
    @Suppress("DEPRECATION")
    fun build_givenDeprecatedRegion_expectSiteIdKeptAndRegionIgnored() {
        val config = MessagingInAppModuleConfig.Builder("site", Region.EU).build()

        config.siteId shouldBeEqualTo "site"
        config.region shouldBeEqualTo Region.EU
        // In-app uses the SDK's region, US when the SDK isn't set up
        config.dataCenter shouldBeEqualTo "us"
    }

    @Test
    fun setNotificationInboxAccessibilityLabels_expectLabelsOnConfig() {
        val config = MessagingInAppModuleConfig.Builder(siteId = "site")
            .setNotificationInboxAccessibilityLabels(
                NotificationInboxAccessibilityLabels(
                    bell = "Aviseringar",
                    bellWithUnreadCount = { count -> if (count == 1) "1 oläst avisering" else "$count olästa aviseringar" },
                    loadingIndicator = "Laddar",
                    emptyState = "Inga aviseringar"
                )
            )
            .build()

        config.notificationInboxAccessibilityLabels.bell shouldBeEqualTo "Aviseringar"
        config.notificationInboxAccessibilityLabels.bellWithUnreadCount?.invoke(1) shouldBeEqualTo "1 oläst avisering"
        config.notificationInboxAccessibilityLabels.bellWithUnreadCount?.invoke(4) shouldBeEqualTo "4 olästa aviseringar"
        config.notificationInboxAccessibilityLabels.loadingIndicator shouldBeEqualTo "Laddar"
        config.notificationInboxAccessibilityLabels.emptyState shouldBeEqualTo "Inga aviseringar"
    }
}

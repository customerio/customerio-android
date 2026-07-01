package io.customer.messagingpush

import io.customer.commontest.core.JUnit5Test
import io.customer.messagingpush.livenotification.LiveNotificationAsset
import io.customer.messagingpush.livenotification.LiveNotificationType
import org.amshove.kluent.internal.assertEquals
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldContainSame
import org.junit.jupiter.api.Test

class MessagingPushModuleConfigTest : JUnit5Test() {

    @Test
    fun test_toString_generatesCorrectRepresentation() {
        val config = MessagingPushModuleConfig.default()

        val actual = config.toString()
        assertEquals("MessagingPushModuleConfig(autoTrackPushEvents=true, notificationCallback=null, pushClickBehavior=ACTIVITY_PREVENT_RESTART, liveNotificationBranding=null, liveNotificationTypes=[], liveNotificationAssets={})", actual)
    }

    @Test
    fun enableLiveNotificationTypes_mapsBuiltInTypesToIdentifiers() {
        val config = MessagingPushModuleConfig.Builder()
            .enableLiveNotificationTypes(
                LiveNotificationType.DELIVERY_TRACKING,
                LiveNotificationType.LIVE_SCORE
            )
            .build()

        config.liveNotificationTypes shouldContainSame setOf(
            LiveNotificationType.DELIVERY_TRACKING.identifier,
            LiveNotificationType.LIVE_SCORE.identifier
        )
    }

    @Test
    fun enableTypes_builtInAndCustom_areAdditive() {
        val config = MessagingPushModuleConfig.Builder()
            .enableLiveNotificationTypes(LiveNotificationType.AUCTION_BID)
            .enableCustomLiveNotificationTypes("com.acme.ride", "com.acme.workout")
            .build()

        config.liveNotificationTypes shouldContainSame setOf(
            LiveNotificationType.AUCTION_BID.identifier,
            "com.acme.ride",
            "com.acme.workout"
        )
    }

    @Test
    fun registerLiveNotificationAsset_storesAssetByKeyAndType() {
        val bytes = byteArrayOf(1, 2, 3)
        val config = MessagingPushModuleConfig.Builder()
            .registerLiveNotificationAsset("logo-drawable", 42)
            .registerLiveNotificationAsset("logo-bytes", bytes)
            .build()

        config.liveNotificationAssets.keys shouldContainSame setOf("logo-drawable", "logo-bytes")
        config.liveNotificationAssets["logo-drawable"]!!.shouldBeInstanceOf<LiveNotificationAsset.Drawable>()
        (config.liveNotificationAssets["logo-drawable"] as LiveNotificationAsset.Drawable).resId shouldBeEqualTo 42
        config.liveNotificationAssets["logo-bytes"]!!.shouldBeInstanceOf<LiveNotificationAsset.Bytes>()
    }
}
